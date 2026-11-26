"""Execute selected UNMODIFIED 0.3.48 AArch64 functions under Unicorn.

No process, phone, syscall, real socket, IKE, IMS, call, or SMS is started.
External allocation/JSON/policy/logging/network boundaries are explicit stubs.
This is controlled instruction replay, not an Android runtime test.
"""
from pathlib import Path
import json
import struct
import sys
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn import arm64_const as arm

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools/engine_iface"))
from inspect_native import GoImage, SOURCE_SHA256

REGS = [getattr(arm, "UC_ARM64_REG_X" + str(i)) for i in range(31)]
STACK, END = 0x31080000, 0x32000000
PREFIX = "vowifi_engine/internal/"


class Machine:
    def __init__(self, image):
        self.image = image
        self.u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        self.u.mem_map(0, 0x1000000)
        for segment in image.elf.iter_segments():
            if segment["p_type"] == "PT_LOAD":
                self.u.mem_write(segment["p_vaddr"], segment.data())
        for address, size in [(0x30000000, 0x10000), (0x31000000, 0x100000), (END, 0x1000), (0x40000000, 0x1000000)]:
            self.u.mem_map(address, size)
        self.heap = 0x40000000
        self.hooks = {}
        self.u.hook_add(UC_HOOK_CODE, self.hook)

    def reset(self):
        for reg in REGS:
            self.u.reg_write(reg, 0)
        self.u.reg_write(arm.UC_ARM64_REG_SP, STACK)
        self.u.reg_write(arm.UC_ARM64_REG_CPACR_EL1, 3 << 20)
        self.set(28, 0x30000000)
        self.set(29, STACK)
        self.set(30, END)
        self.hooks = {}
        self.returned = False

    def get(self, r): return self.u.reg_read(REGS[r])
    def set(self, r, value): self.u.reg_write(REGS[r], value)
    def q(self, address): return struct.unpack("<Q", self.u.mem_read(address, 8))[0]
    def writeq(self, address, *values): self.u.mem_write(address, struct.pack("<" + "Q" * len(values), *values))
    def ret(self): self.u.reg_write(arm.UC_ARM64_REG_PC, self.get(30))
    def address(self, name): return self.image.functions[name][0]
    def stub(self, name, action): self.hooks[self.address(name)] = action

    def hook(self, uc, address, size, unused):
        if address == END:
            self.returned = True
            uc.emu_stop()
        elif address in self.hooks:
            self.hooks[address](self)

    def alloc(self, size):
        pointer = self.heap
        self.heap += (max(size, 8) + 15) & ~15
        self.u.mem_write(pointer, b"\0" * max(size, 8))
        return pointer

    def string(self, value):
        value = value.encode() if isinstance(value, str) else value
        pointer = self.alloc(len(value))
        self.u.mem_write(pointer, value)
        return pointer, len(value)

    def read_string(self, pointer, length): return bytes(self.u.mem_read(pointer, length))

    def run(self, name):
        self.u.emu_start(self.address(name), END + 4, count=500000)
        assert self.returned, "Instruction budget exhausted"


def succeed(m):
    for i in range(5): m.set(i, 0)
    m.ret()


def config(m, selector):
    cfg = m.alloc(0x400)
    m.writeq(cfg, *m.string("0000000000000000000"))
    m.writeq(cfg + 0x18, *m.string("999"))
    m.writeq(cfg + 0x28, *m.string("99"))
    m.writeq(cfg + 0x38, 2)
    m.writeq(cfg + 0x70, *m.string(selector))
    m.u.mem_write(cfg + 0xA8, b"\1")
    m.writeq(cfg + 0xB0, *m.string("000000000000000"))
    m.writeq(cfg + 0xC0, *m.string("000000000000000"))
    return cfg


def transport(m, selector):
    m.reset()
    p, n = m.string(selector)
    m.set(0, p); m.set(1, n)
    m.run(PREFIX + "config.ValidTransport")
    return m.get(0) == 1


def validate(m, selector, create=False):
    m.reset()
    cfg = config(m, selector)
    m.set(0, cfg)
    if create:
        def decoded(v):
            v.set(0, cfg); v.set(1, 0); v.set(2, 0); v.ret()
        m.stub(PREFIX + "config.ParseJSON", decoded)
        m.run(PREFIX + "config.validateCreateBytes")
        return m.get(2) == 0
    for name in ["carrier.ParseProposalLayout", "config.(*CardConfig).validateIKEDHGroup", "config.validateIMSIdentity", "config.validateDNSOverrides", "config.validateOptionalPolicy"]:
        m.stub(PREFIX + name, succeed)
    def error(v):
        v.set(0, 0x123456); v.set(1, 0x123458); v.ret()
    m.stub("fmt.errorf", error)
    m.run(PREFIX + "config.(*CardConfig).Validate")
    return m.get(0) == 0


def select(m, selector, names):
    m.reset()
    rows = m.alloc(120 * len(names))
    for index, name in enumerate(names):
        row = rows + 120 * index
        m.writeq(row, index + 1, *m.string(name), *m.string("wifi"))
        m.u.mem_write(row + 64, b"\1")
    # Snapshot receiver: ten Go ABI words; selector occupies x10/x11.
    for reg, value in enumerate([1, 0, 0, 0, 0, 0, 0, rows, len(names), len(names), *m.string(selector), 0]):
        m.set(reg, value)
    m.writeq(0x9BFB30, 0x123400, 0x123408)  # Mock initialized error objects.
    m.writeq(0x9BFB40, 0x123500, 0x123508)
    selected = []
    def allocation(v):
        v.set(0, v.alloc(v.get(2) * 120)); v.ret()
    def binding(v):
        selected.append(v.read_string(v.get(1), v.get(2)))
        out = v.u.reg_read(arm.UC_ARM64_REG_SP) + 8
        v.u.mem_write(out, b"\0" * 136)
        v.writeq(out, v.get(0), v.get(1), v.get(2), v.get(3), v.get(4))
        v.set(0, 1); v.ret()
    m.stub("runtime.makeslice", allocation)
    m.stub(PREFIX + "underlay.bindingFor", binding)
    m.stub(PREFIX + "underlay.Snapshot.SelectObserved.func1", lambda v: v.ret())
    m.run(PREFIX + "underlay.Snapshot.SelectObserved")
    return m.get(0) == 0, selected


def control(m, direct=False, platform_error=False):
    m.reset()
    binding, closure, error = m.alloc(136), m.alloc(24), m.alloc(16)
    m.writeq(binding, 55, *m.string("tun0"))
    m.u.mem_write(binding + 0x78, bytes([direct]))
    m.writeq(closure, 0, binding, error)
    m.set(26, closure); m.set(0, 42)
    calls = []
    def platform(v):
        calls.append(("platform", v.get(0), v.get(1)))
        v.set(0, 0x1234 if platform_error else 0); v.set(1, 0); v.ret()
    def device(v):
        calls.append(("device", v.get(0), v.get(1), v.get(2), v.read_string(v.get(3), v.get(4)).decode()))
        v.set(0, 0); v.set(1, 0); v.ret()
    m.stub(PREFIX + "underlay.bindPlatformNetwork", platform)
    m.stub("syscall.SetsockoptString", device)
    m.run(PREFIX + "underlay.Binding.Control.func1")
    return calls


def main():
    image = GoImage(); machine = Machine(image); checks = []
    def check(value, name):
        assert value, name
        checks.append(name)
    for value in ["wifi", "sim1", "sim2", "auto", "route:system", "iface:tun0", "iface:wlan0", "iface:rmnet_data0", "iface:" + "a" * 15]:
        check(transport(machine, value), "accept " + value)
    for value in ["", "tun0", "mode:wifi", "iface:", "iface:.", "iface:..", "iface:a/b", "iface:a:b", "iface:" + "a" * 16]:
        check(not transport(machine, value), "reject " + value)
    for value in range(256):
        allowed = 48 <= value <= 57 or 65 <= value <= 90 or 97 <= value <= 122 or value in (45, 46, 95)
        check(transport(machine, b"iface:a" + bytes([value]) + b"b") == allowed, "interface byte " + str(value))
    for value in ["wifi", "iface:tun0", "iface:wlan0", "route:system"]:
        check(validate(machine, value), "runtime CardConfig.Validate " + value)
        check(validate(machine, value, create=True) == (value == "wifi"), "strict creation template " + value)
    check(not validate(machine, "iface:a/b"), "runtime rejects invalid interface")
    for selector, rows, expected in [("iface:tun0", ["wlan0", "tun0"], [b"tun0"]), ("iface:tun0", ["tun01", "tun"], []), ("iface:wifi", ["wlan0", "wifi"], [b"wifi"]), ("iface:missing", ["wlan0"], [])]:
        ok, chosen = select(machine, selector, rows)
        check(chosen == expected and ok == bool(expected), "exact selection/no fallback " + selector)
    check(control(machine) == [("platform", 55, 42), ("device", 42, 1, 25, "tun0")], "platform bind precedes SO_BINDTODEVICE")
    check(control(machine, direct=True) == [("device", 42, 1, 25, "tun0")], "direct flag skips only platform bind")
    check(control(machine, platform_error=True) == [("platform", 55, 42)], "platform failure prevents device bind")
    print(json.dumps({"passed": len(checks), "engine_sha256": SOURCE_SHA256, "binary_modified": False, "limits": "Unicorn instruction replay; JSON/policy/allocation/bindingFor/logging/syscalls stubbed; no Android or carrier execution."}, indent=2))


if __name__ == "__main__":
    main()
