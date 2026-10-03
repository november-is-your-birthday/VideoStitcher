import re
import sys
import xml.etree.ElementTree as ET

# 用法: python uiparse.py <ui.xml> <cmd> <arg>
#   center_text  <精确text>      -> 输出 "x y"（首个匹配节点的中心）
#   center_tcon  <包含text>      -> 同上，包含匹配
#   center_resid <id后缀>        -> 同上，按 resource-id 后缀匹配
#   listtexts    <id后缀>        -> 逐行输出所有匹配节点的 text
#   exists_text  <精确text>      -> 输出 found/notfound


def centers(node):
    b = node.get('bounds') or ''
    m = re.findall(r'-?\d+', b)
    if len(m) < 4:
        return None
    x1, y1, x2, y2 = map(int, m[:4])
    return (x1 + x2) // 2, (y1 + y2) // 2


def main():
    path, cmd, arg = sys.argv[1], sys.argv[2], sys.argv[3]
    root = ET.parse(path).getroot()
    for n in root.iter('node'):
        text = n.get('text') or ''
        rid = n.get('resource-id') or ''
        if cmd == 'center_text':
            hit = text == arg
        elif cmd == 'center_tcon':
            hit = arg in text
        elif cmd == 'center_resid':
            hit = rid.endswith(':id/' + arg)
        elif cmd == 'listtexts':
            if rid.endswith(':id/' + arg):
                print(text)
            continue
        elif cmd == 'exists_text':
            if text == arg:
                print('found')
                return
            continue
        else:
            return
        if hit:
            c = centers(n)
            if c:
                print(c[0], c[1])
            return


main()
