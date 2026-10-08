"""Compare a frozen source fingerprint with HEAD and a worktree without equating APKs to commits."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess

TEXT_SUFFIXES = {'.java', '.xml', '.gradle', '.properties', '.txt', '.json', '.md', '.pro', '.html', '.css', '.js'}


def digest(data):
    return hashlib.sha256(data).hexdigest().upper()


def match_form(data, expected, suffix):
    if data is None:
        return None
    if digest(data) == expected:
        return 'exact'
    if suffix.lower() in TEXT_SUFFIXES:
        lf = data.replace(b'\r\n', b'\n')
        if digest(lf) == expected:
            return 'lf-checkout'
        if digest(lf.replace(b'\n', b'\r\n')) == expected:
            return 'crlf-checkout'
    return None


def validated_path(value):
    if not isinstance(value, str) or re.search(r'[\\<>:"|?*\x00-\x1f\x7f]', value):
        raise ValueError('Unsupported fingerprint path')
    path = PurePosixPath(value)
    if not value.startswith('app/src/main/') or '..' in path.parts or path.as_posix() != value:
        raise ValueError('Fingerprint path outside main source scope')
    return path


def classify(work, head):
    if work and head:
        return 'both-match-frozen'
    if work:
        return 'only-worktree-matches-frozen'
    if head:
        return 'only-head-matches-frozen'
    return 'neither-matches-frozen'


def compare(root, fingerprint, ref='HEAD'):
    root = root.resolve()
    def git(*args):
        return subprocess.check_output(['git', *args], cwd=root)
    commit = git('rev-parse', '--verify', '--end-of-options', ref + '^{commit}').decode().strip()
    if not re.fullmatch(r'[0-9a-f]{40,64}', commit):
        raise ValueError('Unresolved comparison commit')
    frozen = json.loads(fingerprint.read_text(encoding='utf-8-sig'))
    entries = frozen['files']
    expected = {}
    seen = set()
    for entry in entries:
        path = validated_path(entry['path']).as_posix()
        checksum = entry['sha256'].upper()
        if path.casefold() in seen or not re.fullmatch(r'[0-9A-F]{64}', checksum):
            raise ValueError('Duplicate path or invalid checksum')
        seen.add(path.casefold())
        expected[path] = checksum
    tree = {}
    for row in git('ls-tree', '-r', '-z', commit, '--', 'app/src/main').split(b'\0'):
        if not row:
            continue
        metadata, name = row.split(b'\t', 1)
        mode, kind, identity = metadata.decode().split()
        if kind == 'blob':
            tree[name.decode('utf-8')] = (mode, identity)
    paths = []
    work_matches = {}
    for name, checksum in expected.items():
        file = root / name
        if not file.resolve().is_relative_to(root):
            raise ValueError('Source symlink escapes workspace')
        if not file.is_file():
            work_matches[name] = None
            continue
        data = file.read_bytes()
        work_matches[name] = match_form(data, checksum, file.suffix)
        paths.append(name)
    work_git = {}
    for start in range(0, len(paths), 40):
        batch = paths[start:start + 40]
        identities = git('hash-object', '--', *batch).decode().splitlines()
        if len(identities) != len(batch):
            raise ValueError('Incomplete worktree hashing')
        work_git.update(zip(batch, identities))
    records = []
    for name, checksum in expected.items():
        head_form = None
        entry = tree.get(name)
        if entry is not None:
            mode, identity = entry
            if mode == '120000':
                raise ValueError('Symlink blob is not a source-file comparison')
            if work_git.get(name) == identity:
                head_form = 'git-normalized-match' if work_matches[name] else None
            else:
                head_form = match_form(git('show', commit + ':' + name), checksum, Path(name).suffix)
        records.append(dict(path=name, status=classify(work_matches[name], head_form),
                            worktreeMatch=work_matches[name], headMatch=head_form,
                            headTracked=entry is not None, worktreePresent=name in work_git))
    untracked = git('ls-files', '--others', '--exclude-standard', '-z', '--', 'app/src/main').decode('utf-8').split('\0')
    added = sorted((set(tree) | {name for name in untracked if name}) - set(expected))
    return dict(fingerprintSha256=digest(fingerprint.read_bytes()), recordedParent=frozen.get('head'),
                comparedCommit=commit, fingerprintFiles=len(records), counts=dict(Counter(r['status'] for r in records)),
                records=records, filesNotInFingerprint=added,
                limitations=['Hash and checkout-newline comparison, not semantic or APK equivalence.',
                             'Equal Git blob identities reuse a matching worktree result; headMatch labels that shortcut explicitly.',
                             'The recorded parent does not include the frozen uncommitted source.',
                             'Matching a historical file does not prove it should ship unchanged.'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('fingerprint', type=Path)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--ref', default='HEAD')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = compare(args.root, args.fingerprint, args.ref)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: result[key] for key in ('comparedCommit', 'fingerprintFiles', 'counts')}, indent=2))
