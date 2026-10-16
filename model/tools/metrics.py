def token_spans(tags: list[str]) -> set[tuple[str, int, int]]:
    spans, active, start = (set(), None, 0)
    for index, tag in enumerate(tags + ['O']):
        prefix, kind = tag.split('-', 1) if '-' in tag else ('O', None)
        if active is not None and (prefix != 'I' or active != kind):
            spans.add((active, start, index))
            active = None
        if kind is not None and active is None:
            active, start = (kind, index)
    return spans
