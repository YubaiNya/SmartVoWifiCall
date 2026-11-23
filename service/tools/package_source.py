"""Package source and pinned local build dependencies, excluding private device data and signing keys."""
import hashlib,json,pathlib,zipfile
root=pathlib.Path(__file__).resolve().parents[1]
out=root/'dist/SmartVoWifiCall-source.zip'
# Device repair/provisioning helpers contain incident-specific identifiers and
# remain in the private workspace. They are not product installation tools.
public_tools={'build.py','package_source.py','verify_artifacts.py','isolate_engine_log_pool.py','generate_logo.py'}
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
 for dirname in ['app','module','tests','tools','deps','docs']:
  directory=root/dirname
  if not directory.exists():continue
  for p in sorted(directory.rglob('*')):
   rel=p.relative_to(root)
   if not p.is_file() or any(part.startswith('.') or part=='__pycache__' for part in rel.parts):continue
   if dirname=='app' and len(rel.parts)>1 and rel.parts[1]=='build':continue
   if p.suffix in {'.class','.pyc'}:continue
   if dirname=='tools' and rel.parts[1]!='engine_iface' and p.name not in public_tools:continue
   if dirname=='deps' and (p.name=='mihomo-android-arm64'):continue
   z.write(p,rel.as_posix())
 for name in ['README.md','.gitignore','build.gradle.kts','settings.gradle.kts','gradle.properties']:
  if (root/name).exists():
   z.write(root/name,name)
hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((root/'dist').iterdir()) if p.suffix in ['.apk','.zip']}
(root/'dist/SHA256.json').write_text(json.dumps(hashes,indent=2)+'\n')
print('Source package built; signing keys and device data excluded.')
