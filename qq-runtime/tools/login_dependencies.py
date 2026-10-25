#!/usr/bin/env python3
"""Resolve pinned Maven JVM artifacts once; subsequent builds verify the lock."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / 'cache' / 'maven'
LOCK = ROOT / 'login-dependencies.json'
BASE = 'https://repo.maven.apache.org/maven2/'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
SEEDS = {
    'runtime': [('org.ntqqrev', 'acidify-core-jvm', '1.6.3'),
                ('io.ktor', 'ktor-client-cio-jvm', '3.5.1'),
                ('org.jetbrains.kotlinx', 'atomicfu-jvm', '0.33.0')],
    'compiler': [('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.10'),
                 ('org.jetbrains.kotlin', 'kotlin-serialization-compiler-plugin-embeddable', '2.4.10')],
}


def location(coordinate, extension):
    group, name, version = coordinate
    return f'{group.replace(".", "/")}/{name}/{version}/{name}-{version}.{extension}'


def download(relative):
    path = CACHE / relative
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(BASE + relative, timeout=45) as response:
            path.write_bytes(response.read())
    return path


def dependencies(coordinate):
    root = ET.fromstring(download(location(coordinate, 'pom')).read_bytes())
    def value(node, key, default=''):
        return node.findtext('m:' + key, default=default, namespaces=NS)
    properties = {'project.version': coordinate[2], 'project.groupId': coordinate[0]}
    props = root.find('m:properties', NS)
    if props is not None:
        properties.update({x.tag.rsplit('}', 1)[-1]: x.text for x in props})
    result = []
    for node in root.findall('m:dependencies/m:dependency', NS):
        if value(node, 'scope', 'compile') not in ('compile', 'runtime') or value(node, 'optional') == 'true':
            continue
        group, name, version = (value(node, x) for x in ('groupId', 'artifactId', 'version'))
        if not version:
            raise RuntimeError(f'Unspecified Maven version: {coordinate}: {group}:{name}')
        for key, val in properties.items():
            version = version.replace('${' + key + '}', val)
        if '${' in version:
            raise RuntimeError(f'Unresolved Maven property: {coordinate}: {version}')
        # Compiler's reflect/coroutines dependencies explicitly exclude transitives.
        excluded = any(value(x, 'groupId') == '*' and value(x, 'artifactId') == '*'
                       for x in node.findall('m:exclusions/m:exclusion', NS))
        result.append(((group, name, version), excluded))
    return result


def version_key(version):
    return tuple(int(x) if x.isdigit() else x for x in re.split(r'[.-]', version))


def resolve(seeds):
    selected = {}
    pending = [(x, False) for x in seeds]
    visited = set()
    while pending:
        batch, pending = pending, []
        batch = [x for x in batch if x not in visited]
        visited.update(batch)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            deps = list(pool.map(lambda x: [] if x[1] else dependencies(x[0]), batch))
        for (coordinate, _), children in zip(batch, deps):
            key = coordinate[:2]
            if key not in selected or version_key(coordinate[2]) > version_key(selected[key][2]):
                selected[key] = coordinate
                pending.extend(children)
    return sorted(selected.values())


def ensure():
    if LOCK.exists():
        lock = json.loads(LOCK.read_text())
    else:
        lock = {'repository': BASE, 'sets': {}}
        for group, seeds in SEEDS.items():
            coordinates = resolve(seeds)
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                paths = list(pool.map(lambda c: download(location(c, 'jar')), coordinates))
            lock['sets'][group] = [dict(coordinate=list(c), path=p.relative_to(CACHE).as_posix(),
                                        sha256=hashlib.sha256(p.read_bytes()).hexdigest())
                                   for c, p in zip(coordinates, paths)]
        LOCK.write_text(json.dumps(lock, indent=2) + '\n')
    result = {}
    for group, artifacts in lock['sets'].items():
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            paths = list(pool.map(lambda a: download(a['path']), artifacts))
        for item, path in zip(artifacts, paths):
            if hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
                raise RuntimeError('Dependency checksum mismatch: ' + str(path))
        result[group] = paths
    return result


if __name__ == '__main__':
    for group, paths in ensure().items():
        print(group, len(paths), sum(p.stat().st_size for p in paths), flush=True)
