"""Inspect aggregate CSV metadata; optionally provision ignored local test fixtures."""
import argparse
import csv
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIELDS = ['Id', 'Phone', 'Name', 'Post', 'TopOrgName', 'FullOrgName']

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--csv', type=Path, required=True)
    parser.add_argument('--copy-fixtures', action='store_true')
    args = parser.parse_args()
    with args.csv.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != FIELDS:
            raise ValueError('CSV header must be ' + ','.join(FIELDS))
        rows = list(reader)
    if not rows or any(None in row or any(row.get(k) is None for k in FIELDS) for row in rows):
        raise ValueError('CSV contains empty or malformed rows')
    report = {'rows': len(rows), 'bytes': args.csv.stat().st_size,
              'fields': {k: {'empty': sum(not row[k].strip() for row in rows),
                             'distinct': len({row[k] for row in rows}),
                             'max_length': max(len(row[k]) for row in rows)} for k in FIELDS}}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.copy_fixtures:
        for variant in ['debug', 'androidTest']:
            destination = ROOT / 'app/src' / variant / 'assets/private/members.csv'
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(args.csv, destination)
        print('Provisioned ignored debug and device-test fixtures. Release assets are unchanged.')

if __name__ == '__main__':
    main()
