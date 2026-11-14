"""Convert pinned FrequencyWords data to the Gag keyboard's offline word table."""
import argparse
import gzip
import hashlib
from pathlib import Path
import re
import unicodedata


INPUTS = {
    "English": "5351ff405b1126ef555791dd4d9798a48e3e9a501a9fc481a9da957752cfb458",
    "German": "d9e50546fd7e8b6fe6542a2b33c51d1331092b2a3916ec09f80d97856068705b",
}


def read_words(path, expected):
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError(f"Unexpected source bytes for {path.name}")
    for row in data.decode("utf-8").splitlines():
        word, count = row.rsplit(" ", 1)
        word = unicodedata.normalize("NFC", word.lower())
        if re.fullmatch("[a-zäöüß]{2,32}", word):
            yield word, int(count)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("english", type=Path)
    parser.add_argument("german", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    words = {}
    for path, expected in zip((args.english, args.german), INPUTS.values()):
        for word, count in read_words(path, expected):
            words[word] = max(count, words.get(word, 0))
    payload = "".join(f"{word}\t{count}\n" for word, count in sorted(words.items())).encode("utf-8")
    args.output.write_bytes(gzip.compress(payload, mtime=0))


if __name__ == "__main__":
    main()
