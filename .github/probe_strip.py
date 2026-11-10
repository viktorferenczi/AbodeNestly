import re,sys
p='apps/ios/AbodeNestly.xcodeproj/project.pbxproj'
names=sys.argv[1:]
s=open(p).read()
lines=s.split('\n')
# split into blocks: top-level objects start with 2 tabs + 24 hex id
ids=set()
def blocks(lines):
    out=[];cur=[]
    for l in lines:
        if re.match(r'^\t\t[0-9A-F]{24} ',l) and cur: out.append(cur);cur=[]
        cur.append(l)
    out.append(cur);return out
for n in names:
    for l in lines:
        m=re.match(r'^\t\t([0-9A-F]{24}) /\* (XCRemoteSwiftPackageReference|XCLocalSwiftPackageReference) "([^"]*)"',l)
        if m and (m.group(3)==n or m.group(3).endswith('/'+n)): ids.add(m.group(1))
changed=True
while changed:
    changed=False
    for l in lines:
        m=re.match(r'^\t\t([0-9A-F]{24}) ',l)
        if m and m.group(1) not in ids and any(i in l for i in ids) and ('package =' in l or 'productRef' in l):
            ids.add(m.group(1));changed=True
    # multiline product dep blocks
    for b in blocks(lines):
        t='\n'.join(b)
        m=re.match(r'^\t\t([0-9A-F]{24}) ',b[0])
        if m and m.group(1) not in ids and re.search(r'package = ('+'|'.join(ids)+')',t) if ids else False:
            ids.add(m.group(1));changed=True
out=[]
for b in blocks(lines):
    m=re.match(r'^\t\t([0-9A-F]{24}) ',b[0])
    if m and m.group(1) in ids: continue
    out+= [l for l in b if not (re.match(r'^\t\t\t\t[0-9A-F]{24} /\*',l) and any(i in l for i in ids))]
open(p,'w').write('\n'.join(out))
print(sorted(ids))
