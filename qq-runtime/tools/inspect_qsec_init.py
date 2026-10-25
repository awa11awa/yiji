#!/usr/bin/env python3
"""Run local ELF constructors with synthetic memory syscalls; inspect JNI entry.

Analysis only. Requires unicorn, capstone and pyelftools. No networking, JNI
execution, original file modifications, or vendor bytes in its JSON report.
"""
import argparse
import json
import re
import struct
from pathlib import Path
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UcError, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_INTR
from unicorn.arm64_const import *


def inspect(path):
    base, stack, sentinel = 0x100000, 0x300000, 0x120000
    vm = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    vm.mem_map(base, 0x30000)
    vm.mem_map(stack, 0x100000)
    with path.open('rb') as source:
        elf = ELFFile(source)
        for segment in elf.iter_segments():
            if segment['p_type'] == 'PT_LOAD':
                vm.mem_write(base + segment['p_vaddr'], segment.data())
        dynamic = next(s for s in elf.iter_segments() if s['p_type'] == 'PT_DYNAMIC')
        symbols = list(dynamic.iter_symbols())
        entry = next(s for s in symbols if s.name == 'JNI_OnLoad')
        tags = {t.entry.d_tag: t.entry.get('d_val') for t in dynamic.iter_tags()}
        for offset in range(0, tags['DT_RELASZ'], 24):
            target, info, addend = struct.unpack('<QQq', vm.mem_read(base + tags['DT_RELA'] + offset, 24))
            if info & 0xffffffff == 1027:
                vm.mem_write(base + target, struct.pack('<Q', base + addend))
    vm.reg_write(UC_ARM64_REG_SP, stack + 0xf0000)
    vm.reg_write(UC_ARM64_REG_X30, sentinel)
    vm.reg_write(UC_ARM64_REG_CPACR_EL1, 3 << 20)
    allocations = []
    events = []
    next_mapping = 0x500000
    handles = {}

    def syscall(uc, number, data):
        nonlocal next_mapping
        call = uc.reg_read(UC_ARM64_REG_X8)
        args = [uc.reg_read(UC_ARM64_REG_X0 + i) for i in range(6)]
        if call == 222:
            length = (args[1] + 4095) & -4096
            address = args[0] or next_mapping
            if not args[3] & 0x10:
                next_mapping = address + length + 4096
                uc.mem_map(address, length)
                allocations.append((address, length))
            uc.reg_write(UC_ARM64_REG_X0, address)
        elif call in (215, 226):
            uc.reg_write(UC_ARM64_REG_X0, 0)
        elif call == 56:
            name = bytes(uc.mem_read(args[1], 256)).split(b'\0')[0].decode()
            if name != '/proc/self/status' or args[2] != 0:
                raise RuntimeError('Only read-only /proc/self/status is supported: ' + name)
            handles[100] = Path('/proc/self/status').read_bytes()
            uc.reg_write(UC_ARM64_REG_X0, 100)
        elif call == 63 and args[0] in handles:
            value = handles[args[0]][:args[2]]
            uc.mem_write(args[1], value)
            handles[args[0]] = handles[args[0]][len(value):]
            uc.reg_write(UC_ARM64_REG_X0, len(value))
        elif call == 57 and args[0] in handles:
            del handles[args[0]]
            uc.reg_write(UC_ARM64_REG_X0, 0)
        else:
            detail = {'syscall': call, 'args': args}
            if call == 56:
                detail['path'] = bytes(uc.mem_read(args[1], 256)).split(b'\0')[0].decode(errors='replace')
            events.append(detail)
            raise RuntimeError('Unmodelled syscall: ' + str(detail))
    vm.hook_add(UC_HOOK_INTR, syscall)
    report = {'analysis': 'synthetic offline constructor execution', 'syscalls': events}
    try:
        vm.emu_start(base + tags['DT_INIT'], sentinel, count=4000000)
        report['init_returned'] = vm.reg_read(UC_ARM64_REG_PC) == sentinel
    except (UcError, RuntimeError) as error:
        report['error'] = str(error)
        report['pc'] = hex(vm.reg_read(UC_ARM64_REG_PC))
    jni = bytes(vm.mem_read(base + entry['st_value'], entry['st_size']))
    report['jni_onload_address'] = hex(entry['st_value'])
    report['jni_onload_disassembly'] = [f'{i.address:x}: {i.mnemonic} {i.op_str}'
        for i in Cs(CS_ARCH_ARM64, CS_MODE_ARM).disasm(jni, entry['st_value'])]
    memory = bytes(vm.mem_read(base, 0x30000))
    report['class_names'] = sorted(set(m.decode() for m in re.findall(rb'com/[a-zA-Z0-9_/$]+', memory)))
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('library', type=Path)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    result = inspect(args.library)
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False, indent=2))
