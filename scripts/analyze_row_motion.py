"""Strict shadow-only row motion trace reader. No inferred records or silent schema loss."""
import argparse
import json
import re
from pathlib import Path

RECORD = re.compile(r"ROW_MOTION accepted=(true|false) previousMs=(-?\d+) currentMs=(\d+) dyMilliPx=(-?\d+) bands=(\d+) preparedHeight=(\d+) sourceHeight=(\d+) costMs=(\d+)(?: cpuUs=(\d+))?")

def parse(text):
    raw=0
    records=[]
    for line in text.splitlines():
        if "ROW_MOTION " not in line: continue
        raw+=1
        match=RECORD.fullmatch(line[line.index("ROW_MOTION "):].strip())
        if not match: continue
        accepted=match[1]=="true"
        previous,current,dy,bands,height,source,cost=map(int,match.groups()[1:8])
        cpu=int(match[9]) if match[9] is not None else None
        if height<=0 or source<=0 or bands>4: continue
        if accepted and (previous<0 or not 0<current-previous<=750 or bands<3): continue
        records.append(dict(accepted=accepted,previousMs=previous,currentMs=current,
                            sourceDy=dy/1000*source/height,bands=bands,costMs=cost,cpuUs=cpu))
    return dict(rawRecords=raw,parsedRecords=len(records),complete=raw==len(records),
                acceptedRecords=sum(r['accepted'] for r in records),records=records)

if __name__=='__main__':
    cli=argparse.ArgumentParser(); cli.add_argument('trace',type=Path)
    args=cli.parse_args(); print(json.dumps(parse(args.trace.read_text(encoding='utf-8-sig')),indent=2))
