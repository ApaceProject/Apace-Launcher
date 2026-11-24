#!/usr/bin/env python3
"""Extract binaries from .deb files (ar + tar.xz) into out/<pkg>/."""
import sys, os, tarfile, lzma

def deb_data_members(path):
    """Return list of member file objects in ar archive that start with 'data.tar'."""
    out = []
    with open(path, 'rb') as f:
        magic = f.read(8)
        assert magic == b'!<arch>\n', f'not an ar archive: {path}'
        while True:
            hdr = f.read(60)
            if len(hdr) < 60:
                break
            name = hdr[0:16].decode('ascii', 'replace').strip()
            size = int(hdr[48:58].decode('ascii', 'replace').strip())
            data = f.read(size)
            if size % 2 == 1:
                f.read(1)
            if name.startswith('data.tar'):
                out.append((name, data))
    return out

def main(debs, outdir):
    os.makedirs(outdir, exist_ok=True)
    for deb in debs:
        pkg = os.path.basename(deb).split('_')[0]
        for name, data in deb_data_members(deb):
            print(f'{deb}: {name} ({len(data)} bytes)')
            if name.endswith('.xz'):
                raw = lzma.decompress(data)
            elif name.endswith('.gz'):
                import gzip
                raw = gzip.decompress(data)
            elif name.endswith('.zst'):
                raise SystemExit('zstd member — handle manually')
            else:
                raw = data
            import io
            with tarfile.open(fileobj=io.BytesIO(raw)) as tf:
                for m in tf.getmembers():
                    if not (m.isfile() or m.issym() or m.islnk()):
                        continue
                    dest_root = os.path.join(outdir, pkg)
                    # strip 'data/' prefix
                    rel = m.name
                    if rel.startswith('./'):
                        rel = rel[2:]
                    if rel.startswith('data/'):
                        rel = rel[5:]
                    dest = os.path.join(dest_root, rel)
                    os.makedirs(os.path.dirname(dest), exist_ok=True)
                    if m.issym() or m.islnk():
                        try:
                            os.symlink(m.linkname, dest)
                        except FileExistsError:
                            os.remove(dest); os.symlink(m.linkname, dest)
                        print('  link', m.name, '->', m.linkname)
                    else:
                        with open(dest, 'wb') as g:
                            g.write(tf.extractfile(m).read())
                        os.chmod(dest, m.mode & 0o777 | 0o444)
                        print('  file', m.name, m.size)

if __name__ == '__main__':
    main(sys.argv[1:-1], sys.argv[-1])
