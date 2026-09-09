# -*- coding: utf-8 -*-
"""xlsx 를 의존성 없이 읽는다.

openpyxl 이 없는 환경이라 직접 푼다. xlsx 는 zip 안에 XML 이 든 것뿐이다:
`xl/sharedStrings.xml` 이 문자열 풀이고, `xl/worksheets/sheetN.xml` 이 셀이며
`t="s"` 인 셀의 값은 그 풀의 색인이다.

GBIS 가 신청 없이 내주는 노선·경유·정류소 파일을 읽으려고 만들었다.
"""
import re
import sys
import zipfile
from xml.etree import ElementTree as ET

NS = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'


def shared_strings(z):
    try:
        raw = z.read('xl/sharedStrings.xml')
    except KeyError:
        return []
    out = []
    for si in ET.fromstring(raw).iter(NS + 'si'):
        # <si> 안에 <t> 가 여러 개로 쪼개져 있을 수 있다(서식이 섞인 셀).
        out.append(''.join(t.text or '' for t in si.iter(NS + 't')))
    return out


def col_index(ref):
    """`BC12` → 54 (0부터)."""
    m = re.match(r'([A-Z]+)', ref or '')
    if not m:
        return 0
    n = 0
    for c in m.group(1):
        n = n * 26 + (ord(c) - 64)
    return n - 1


def rows(path, sheet='xl/worksheets/sheet1.xml', limit=None):
    z = zipfile.ZipFile(path)
    ss = shared_strings(z)
    with z.open(sheet) as f:
        n = 0
        for _, el in ET.iterparse(f, events=('end',)):
            if el.tag != NS + 'row':
                continue
            cells = {}
            for c in el.iter(NS + 'c'):
                v = c.find(NS + 'v')
                if v is None or v.text is None:
                    # 인라인 문자열
                    t = c.find(NS + 'is')
                    text = ''.join(x.text or '' for x in t.iter(NS + 't')) if t is not None else ''
                else:
                    text = ss[int(v.text)] if c.get('t') == 's' and int(v.text) < len(ss) else v.text
                cells[col_index(c.get('r'))] = text
            width = (max(cells) + 1) if cells else 0
            yield [cells.get(i, '') for i in range(width)]
            el.clear()
            n += 1
            if limit and n >= limit:
                return


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    path = sys.argv[1]
    sheet = sys.argv[2] if len(sys.argv) > 2 else 'xl/worksheets/sheet1.xml'
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 5
    for r in rows(path, sheet, limit=n):
        print(' | '.join(str(x)[:24] for x in r))


def all_rows(path, limit=None):
    """시트를 여러 장으로 쪼갠 파일을 한 줄기로 읽는다.

    GBIS 는 65,000행마다 시트를 갈아탄다. sheet1 만 읽으면 조용히 잘린 자료를
    보게 된다 — 경유정류소는 그렇게 읽으면 3,672개 노선 중 856개만 나온다.
    """
    z = zipfile.ZipFile(path)
    sheets = sorted(
        (n for n in z.namelist() if re.match(r'xl/worksheets/sheet\d+\.xml$', n)),
        key=lambda n: int(re.search(r'(\d+)', n.rsplit('/', 1)[1]).group(1)))
    n = 0
    for si, sh in enumerate(sheets):
        for r in rows(path, sh):
            if si > 0 and n == 0:
                pass
            n += 1
            yield r
            if limit and n >= limit:
                return
