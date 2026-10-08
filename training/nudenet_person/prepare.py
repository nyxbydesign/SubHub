"""Validate PERSON-only data and create a one-class dataset definition."""
import argparse
import json
from pathlib import Path
from contract import validate_dataset, write_yaml

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('dataset',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--inspect-incomplete',action='store_true',help='Report counts only; never write training YAML')
    args=parser.parse_args()
    report=validate_dataset(args.dataset, require_all=not args.inspect_incomplete, verify_images=True)
    print(json.dumps(report,indent=2))
    if not args.inspect_incomplete:
        if args.output.exists():
            raise ValueError('Choose a new output path; existing dataset definitions are not overwritten')
        args.output.parent.mkdir(parents=True,exist_ok=True)
        write_yaml(args.dataset,args.output)

if __name__=='__main__': main()
