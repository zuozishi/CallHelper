"""Extract the requested version's user-facing notes from CHANGELOG.md."""
import argparse
from pathlib import Path
import re

from build_release import validate_release_tag

ROOT = Path(__file__).resolve().parents[1]


def extract_notes(changelog, version):
    headings = list(re.finditer(r'^##[ \t]+(.+)$', changelog, re.MULTILINE))
    matches = [i for i, heading in enumerate(headings)
               if re.fullmatch(re.escape(version) + r'(?:[ \t]+·[ \t]+[^\r\n]+)?',
                               heading.group(1).strip())]
    if len(matches) != 1:
        raise ValueError('CHANGELOG.md must contain exactly one section for the release version')
    index = matches[0]
    heading = headings[index]
    end = headings[index + 1].start() if index + 1 < len(headings) else len(changelog)
    body = changelog[heading.end():end].strip()
    if not body:
        raise ValueError('Release notes must not be empty')
    return heading.group(0).strip() + '\n\n' + body + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        version = args.tag.removeprefix('v')
        validate_release_tag(args.tag, version)
        notes = extract_notes((ROOT / 'CHANGELOG.md').read_text(encoding='utf-8-sig'), version)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(notes, encoding='utf-8', newline='\n')
    except (ValueError, OSError) as error:
        parser.exit(1, f'Release notes unavailable: {error}\n')


if __name__ == '__main__':
    main()
