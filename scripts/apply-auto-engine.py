#!/usr/bin/env python3
"""Apply the auditable Yay Auto overlay to sing-box v1.12.12 only."""
from pathlib import Path
import hashlib
import subprocess
import sys
root = Path(__file__).resolve().parents[1]
engine = Path(sys.argv[1]).resolve()
expected = '54ed58499d7063136ed52dabf87d179d252425d0'
revision = subprocess.check_output(['git', '-C', str(engine), 'rev-parse', 'HEAD'], text=True).strip()
if revision != expected:
    raise SystemExit('Unexpected engine revision; refusing overlay')
registration = engine / 'protocol/group/urltest.go'
original = subprocess.check_output(['git', '-C', str(engine), 'show', 'HEAD:protocol/group/urltest.go'])
if hashlib.sha256(original).hexdigest() != '23fcd9d3f078c8b2a6a11c9cd1a631253dff456e206713c09c37e248659fbdad':
    raise SystemExit('Unexpected engine registration source')
needle = b'func RegisterURLTest(registry *outbound.Registry) {\n'
patched = original.replace(needle, needle + b'\toutbound.Register[option.YayAutoOutboundOptions](registry, "yay-auto", NewYayAuto)\n')
# Repeat builds accept only this exact overlay, never arbitrary engine edits.
changed = subprocess.check_output(['git','-C',str(engine),'diff','--name-only','HEAD'],text=True).splitlines()
if any(p != 'protocol/group/urltest.go' for p in changed):
    raise SystemExit('Engine has unrelated tracked edits; inspect it before rebuilding')
if registration.read_bytes().replace(b"\r\n", b"\n") not in (original, patched):
    raise SystemExit('Engine registration has unexpected edits')
for source in sorted((root / 'engine/overlay').rglob('*.go')):
    target = engine / source.relative_to(root / 'engine/overlay')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(source.read_bytes())
registration.write_bytes(patched)
print('Applied Yay Auto overlay to verified sing-box v1.12.12')
