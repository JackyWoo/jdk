/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2019, 2022, Arm Limited. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.internal.foreign.abi.aarch64;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import jdk.internal.foreign.abi.ABIDescriptor;
import jdk.internal.foreign.abi.Binding;
import jdk.internal.foreign.abi.CallingSequence;
import jdk.internal.foreign.abi.CallingSequenceBuilder;
import jdk.internal.foreign.abi.DowncallLinker;
import jdk.internal.foreign.abi.LinkerOptions;
import jdk.internal.foreign.abi.UpcallLinker;
import jdk.internal.foreign.abi.SharedUtils;
import jdk.internal.foreign.abi.VMStorage;
import jdk.internal.foreign.abi.aarch64.linux.LinuxAArch64CallArranger;
import jdk.internal.foreign.abi.aarch64.macos.MacOsAArch64CallArranger;
import jdk.internal.foreign.abi.aarch64.windows.WindowsAArch64CallArranger;
import jdk.internal.foreign.Utils;

import java.lang.foreign.SegmentScope;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Optional;

import static jdk.internal.foreign.PlatformLayouts.*;
import static jdk.internal.foreign.abi.aarch64.AArch64Architecture.*;
import static jdk.internal.foreign.abi.aarch64.AArch64Architecture.Regs.*;

/**
 * For the AArch64 C ABI specifically, this class uses CallingSequenceBuilder
 * to translate a C FunctionDescriptor into a CallingSequence, which can then be turned into a MethodHandle.
 *
 * This includes taking care of synthetic arguments like pointers to return buffers for 'in-memory' returns.
 *
 * There are minor differences between the ABIs implemented on Linux, macOS, and Windows
 * which are handled in sub-classes. Clients should access these through the provided
 * public constants CallArranger.LINUX, CallArranger.MACOS, and CallArranger.WINDOWS.
 */
public abstract class CallArranger {
    private static final int STACK_SLOT_SIZE = 8;
    public static final int MAX_REGISTER_ARGUMENTS = 8;

    private static final VMStorage INDIRECT_RESULT = r8;

    // This is derived from the AAPCS64 spec, restricted to what's
    // possible when calling to/from C code.
    //
    // The indirect result register, r8, is used to return a large
    // struct by value. It's treated as an input here as the caller is
    // responsible for allocating storage and passing this into the
    // function.
    //
    // Although the AAPCS64 says r0-7 and v0-7 are all valid return
    // registers, it's not possible to generate a C function that uses
    // r2-7 and v4-7 so they are omitted here.
    protected static final ABIDescriptor C = abiFor(
        new VMStorage[] { r0, r1, r2, r3, r4, r5, r6, r7, INDIRECT_RESULT},
        new VMStorage[] { v0, v1, v2, v3, v4, v5, v6, v7 },
        new VMStorage[] { r0, r1 },
        new VMStorage[] { v0, v1, v2, v3 },
        new VMStorage[] { r9, r10, r11, r12, r13, r14, r15 },
        new VMStorage[] { v16, v17, v18, v19, v20, v21, v22, v23, v24, v25,
                          v26, v27, v28, v29, v30, v31 },
        16,  // Stack is always 16 byte aligned on AArch64
        0,   // No shadow space
        r9, r10  // scratch 1 & 2
    );

    public record Bindings(CallingSequence callingSequence,
                           boolean isInMemoryReturn) {
    }

    public static final CallArranger LINUX = new LinuxAArch64CallArranger();
    public static final CallArranger MACOS = new MacOsAArch64CallArranger();
    public static final CallArranger WINDOWS = new WindowsAArch64CallArranger();

    /**
     * Are variadic arguments assigned to registers as in the standard calling
     * convention, or always passed on the stack?
     *
     * @return true if variadic arguments should be spilled to the stack.
      */
     protected abstract boolean varArgsOnStack();

    /**
     * {@return true if this ABI requires sub-slot (smaller than STACK_SLOT_SIZE) packing of arguments on the stack.}
     */
    protected abstract boolean requiresSubSlotStackPacking();

    /**
     * Are floating point arguments to variadic functions passed in general purpose registers
     * instead of floating point registers?
     *
     * {@return true if this ABI uses general purpose registers for variadic floating point arguments.}
     */
    protected abstract boolean useIntRegsForVariadicFloatingPointArgs();

    /**
     * Should some fields of structs that assigned to registers be passed in registers when there
     * are not enough registers for all the fields of the struct?
     *
     * {@return true if this ABI passes some fields of a struct in registers.}
     */
    protected abstract boolean spillsVariadicStructsPartially();

    /**
     * @return The ABIDescriptor used by the CallArranger for the current platform.
     */
    protected abstract ABIDescriptor abiDescriptor();

    protected TypeClass getArgumentClassForBindings(MemoryLayout layout, boolean forVariadicFunction) {
        return TypeClass.classifyLayout(layout);
    }

    protected CallArranger() {}

    public Bindings getBindings(MethodType mt, FunctionDescriptor cDesc, boolean forUpcall) {
        return getBindings(mt, cDesc, forUpcall, LinkerOptions.empty());
    }

    public Bindings getBindings(MethodType mt, FunctionDescriptor cDesc, boolean forUpcall, LinkerOptions options) {
        CallingSequenceBuilder csb = new CallingSequenceBuilder(abiDescriptor(), forUpcall, options);

        boolean forVariadicFunction = options.isVariadicFunction();

        BindingCalculator argCalc = forUpcall ? new BoxBindingCalculator(true) : new UnboxBindingCalculator(true, forVariadicFunction);
        BindingCalculator retCalc = forUpcall ? new UnboxBindingCalculator(false, forVariadicFunction) : new BoxBindingCalculator(false);

        boolean returnInMemory = isInMemoryReturn(cDesc.returnLayout());
        if (returnInMemory) {
            csb.addArgumentBindings(MemorySegment.class, AArch64.C_POINTER,
                    argCalc.getIndirectBindings());
        } else if (cDesc.returnLayout().isPresent()) {
            Class<?> carrier = mt.returnType();
            MemoryLayout layout = cDesc.returnLayout().get();
            csb.setReturnBindings(carrier, layout, retCalc.getBindings(carrier, layout));
        }

        for (int i = 0; i < mt.parameterCount(); i++) {
            Class<?> carrier = mt.parameterType(i);
            MemoryLayout layout = cDesc.argumentLayouts().get(i);
            if (varArgsOnStack() && options.isVarargsIndex(i)) {
                argCalc.storageCalculator.adjustForVarArgs();
            }
            csb.addArgumentBindings(carrier, layout, argCalc.getBindings(carrier, layout));
        }

        return new Bindings(csb.build(), returnInMemory);
    }

    public MethodHandle arrangeDowncall(MethodType mt, FunctionDescriptor cDesc, LinkerOptions options) {
        Bindings bindings = getBindings(mt, cDesc, false, options);

        MethodHandle handle = new DowncallLinker(abiDescriptor(), bindings.callingSequence).getBoundMethodHandle();

        if (bindings.isInMemoryReturn) {
            handle = SharedUtils.adaptDowncallForIMR(handle, cDesc, bindings.callingSequence);
        }

        return handle;
    }

    public MemorySegment arrangeUpcall(MethodHandle target, MethodType mt, FunctionDescriptor cDesc, SegmentScope session) {
        Bindings bindings = getBindings(mt, cDesc, true);

        if (bindings.isInMemoryReturn) {
            target = SharedUtils.adaptUpcallForIMR(target, true /* drop return, since we don't have bindings for it */);
        }

        return UpcallLinker.make(abiDescriptor(), target, bindings.callingSequence, session);
    }

    private static boolean isInMemoryReturn(Optional<MemoryLayout> returnLayout) {
        return returnLayout
            .filter(GroupLayout.class::isInstance)
            .filter(g -> TypeClass.classifyLayout(g) == TypeClass.STRUCT_REFERENCE)
            .isPresent();
    }

    class StorageCalculator {
        private final boolean forArguments;
        private final boolean forVariadicFunction;
        private boolean forVarArgs = false;

        private final int[] nRegs = new int[] { 0, 0 };
        private long stackOffset = 0;

        public StorageCalculator(boolean forArguments, boolean forVariadicFunction) {
            this.forArguments = forArguments;
            this.forVariadicFunction = forVariadicFunction;
        }

        void alignStack(long alignment) {
            stackOffset = Utils.alignUp(stackOffset, alignment);
        }

        VMStorage stackAlloc(long size, long alignment) {
            assert forArguments : "no stack returns";
            long alignedStackOffset = Utils.alignUp(stackOffset, alignment);

            short encodedSize = (short) size;
            assert (encodedSize & 0xFFFF) == size;

            VMStorage storage =
                AArch64Architecture.stackStorage(encodedSize, (int)alignedStackOffset);
            stackOffset = alignedStackOffset + size;
            return storage;
        }

        VMStorage stackAlloc(MemoryLayout layout) {
            long stackSlotAlignment = requiresSubSlotStackPacking() && !forVarArgs
                    ? layout.byteAlignment()
                    : Math.max(layout.byteAlignment(), STACK_SLOT_SIZE);
            return stackAlloc(layout.byteSize(), stackSlotAlignment);
        }

        VMStorage[] regAlloc(int type, int count) {
            if (nRegs[type] + count <= MAX_REGISTER_ARGUMENTS) {
                ABIDescriptor abiDescriptor = abiDescriptor();
                VMStorage[] source =
                    (forArguments ? abiDescriptor.inputStorage : abiDescriptor.outputStorage)[type];
                VMStorage[] result = new VMStorage[count];
                for (int i = 0; i < count; i++) {
                    result[i] = source[nRegs[type]++];
                }
                return result;
            } else {
                // Any further allocations for this register type must
                // be from the stack.
                nRegs[type] = MAX_REGISTER_ARGUMENTS;
                return null;
            }
        }

        VMStorage[] regAlloc(int type, MemoryLayout layout) {
            boolean spillRegistersPartially = forVariadicFunction && spillsVariadicStructsPartially();

            return spillRegistersPartially ?
                regAllocPartial(type, layout) :
                regAlloc(type, requiredRegisters(layout));
        }

        int requiredRegisters(MemoryLayout layout) {
            return (int)Utils.alignUp(layout.byteSize(), 8) / 8;
        }

        VMStorage[] regAllocPartial(int type, MemoryLayout layout) {
            int availableRegisters = MAX_REGISTER_ARGUMENTS - nRegs[type];
            if (availableRegisters <= 0) {
                return null;
            }

            int requestRegisters = Math.min(requiredRegisters(layout), availableRegisters);
            return regAlloc(type, requestRegisters);
        }

        VMStorage nextStorage(int type, MemoryLayout layout) {
            if (type == StorageType.VECTOR) {
                boolean forVariadicFunctionArgs = forArguments && forVariadicFunction;
                boolean useIntRegsForFloatingPointArgs = forVariadicFunctionArgs && useIntRegsForVariadicFloatingPointArgs();

                if (useIntRegsForFloatingPointArgs) {
                    type = StorageType.INTEGER;
                }
            }

            VMStorage[] storage = regAlloc(type, 1);
            if (storage == null) {
                return stackAlloc(layout);
            }

            return storage[0];
        }

        VMStorage[] nextStorageForHFA(GroupLayout group) {
            final int nFields = group.memberLayouts().size();
            VMStorage[] regs = regAlloc(StorageType.VECTOR, nFields);
            if (regs == null && requiresSubSlotStackPacking() && !forVarArgs) {
                // For the ABI variants that pack arguments spilled to the
                // stack, HFA arguments are spilled as if their individual
                // fields had been allocated separately rather than as if the
                // struct had been spilled as a whole.

                VMStorage[] slots = new VMStorage[nFields];
                for (int i = 0; i < nFields; i++) {
                    slots[i] = stackAlloc(group.memberLayouts().get(i));
                }

                return slots;
            } else {
                return regs;
            }
        }

        void adjustForVarArgs() {
            // This system passes all variadic parameters on the stack. Ensure
            // no further arguments are allocated to registers.
            nRegs[StorageType.INTEGER] = MAX_REGISTER_ARGUMENTS;
            nRegs[StorageType.VECTOR] = MAX_REGISTER_ARGUMENTS;
            forVarArgs = true;
        }
    }

    abstract class BindingCalculator {
        protected final StorageCalculator storageCalculator;

        protected BindingCalculator(boolean forArguments, boolean forVariadicFunction) {
            this.storageCalculator = new StorageCalculator(forArguments, forVariadicFunction);
        }

        protected void spillStructUnbox(Binding.Builder bindings, MemoryLayout layout) {
            // If a struct has been assigned register or HFA class but
            // there are not enough free registers to hold the entire
            // struct, it must be passed on the stack. I.e. not split
            // between registers and stack.

            spillPartialStructUnbox(bindings, layout, 0);
        }

        protected void spillPartialStructUnbox(Binding.Builder bindings, MemoryLayout layout, long offset) {
            while (offset < layout.byteSize()) {
                long copy = Math.min(layout.byteSize() - offset, STACK_SLOT_SIZE);
                VMStorage storage =
                    storageCalculator.stackAlloc(copy, STACK_SLOT_SIZE);
                if (offset + STACK_SLOT_SIZE < layout.byteSize()) {
                    bindings.dup();
                }
                Class<?> type = SharedUtils.primitiveCarrierForSize(copy, false);
                bindings.bufferLoad(offset, type)
                        .vmStore(storage, type);
                offset += STACK_SLOT_SIZE;
            }

            if (requiresSubSlotStackPacking()) {
                // Pad to the next stack slot boundary instead of packing
                // additional arguments into the unused space.
                storageCalculator.alignStack(STACK_SLOT_SIZE);
            }
        }

        protected void spillStructBox(Binding.Builder bindings, MemoryLayout layout) {
            // If a struct has been assigned register or HFA class but
            // there are not enough free registers to hold the entire
            // struct, it must be passed on the stack. I.e. not split
            // between registers and stack.

            long offset = 0;
            while (offset < layout.byteSize()) {
                long copy = Math.min(layout.byteSize() - offset, STACK_SLOT_SIZE);
                VMStorage storage =
                    storageCalculator.stackAlloc(copy, STACK_SLOT_SIZE);
                Class<?> type = SharedUtils.primitiveCarrierForSize(copy, false);
                bindings.dup()
                        .vmLoad(storage, type)
                        .bufferStore(offset, type);
                offset += STACK_SLOT_SIZE;
            }

            if (requiresSubSlotStackPacking()) {
                // Pad to the next stack slot boundary instead of packing
                // additional arguments into the unused space.
                storageCalculator.alignStack(STACK_SLOT_SIZE);
            }
        }

        abstract List<Binding> getBindings(Class<?> carrier, MemoryLayout layout);

        abstract List<Binding> getIndirectBindings();
    }

    class UnboxBindingCalculator extends BindingCalculator {
        protected final boolean forArguments;
        protected final boolean forVariadicFunction;

        UnboxBindingCalculator(boolean forArguments, boolean forVariadicFunction) {
            super(forArguments, forVariadicFunction);
            this.forArguments = forArguments;
            this.forVariadicFunction = forVariadicFunction;
        }

        @Override
        List<Binding> getIndirectBindings() {
            return Binding.builder()
                .unboxAddress()
                .vmStore(INDIRECT_RESULT, long.class)
                .build();
        }

        @Override
        List<Binding> getBindings(Class<?> carrier, MemoryLayout layout) {
            TypeClass argumentClass = getArgumentClassForBindings(layout, forVariadicFunction);
            Binding.Builder bindings = Binding.builder();

            switch (argumentClass) {
                case STRUCT_REGISTER: {
                    assert carrier == MemorySegment.class;
                    VMStorage[] regs = storageCalculator.regAlloc(StorageType.INTEGER, layout);

                    if (regs != null) {
                        int regIndex = 0;
                        long offset = 0;
                        while (offset < layout.byteSize() && regIndex < regs.length) {
                            final long copy = Math.min(layout.byteSize() - offset, 8);
                            VMStorage storage = regs[regIndex++];
                            Class<?> type = SharedUtils.primitiveCarrierForSize(copy, false);
                            if (offset + copy < layout.byteSize()) {
                                bindings.dup();
                            }
                            bindings.bufferLoad(offset, type)
                                    .vmStore(storage, type);
                            offset += copy;
                        }

                        final long bytesLeft = Math.min(layout.byteSize() - offset, 8);
                        if (bytesLeft > 0) {
                            spillPartialStructUnbox(bindings, layout, offset);
                        }
                    } else {
                        spillStructUnbox(bindings, layout);
                    }
                    break;
                }
                case STRUCT_REFERENCE: {
                    assert carrier == MemorySegment.class;
                    bindings.copy(layout)
                            .unboxAddress();
                    VMStorage storage = storageCalculator.nextStorage(
                        StorageType.INTEGER, AArch64.C_POINTER);
                    bindings.vmStore(storage, long.class);
                    break;
                }
                case STRUCT_HFA: {
                    assert carrier == MemorySegment.class;
                    GroupLayout group = (GroupLayout)layout;
                    VMStorage[] regs = storageCalculator.nextStorageForHFA(group);
                    if (regs != null) {
                        long offset = 0;
                        for (int i = 0; i < group.memberLayouts().size(); i++) {
                            VMStorage storage = regs[i];
                            final long size = group.memberLayouts().get(i).byteSize();
                            boolean useFloat = storage.type() == StorageType.VECTOR;
                            Class<?> type = SharedUtils.primitiveCarrierForSize(size, useFloat);
                            if (i + 1 < group.memberLayouts().size()) {
                                bindings.dup();
                            }
                            bindings.bufferLoad(offset, type)
                                    .vmStore(storage, type);
                            offset += size;
                        }
                    } else {
                        spillStructUnbox(bindings, layout);
                    }
                    break;
                }
                case POINTER: {
                    bindings.unboxAddress();
                    VMStorage storage =
                        storageCalculator.nextStorage(StorageType.INTEGER, layout);
                    bindings.vmStore(storage, long.class);
                    break;
                }
                case INTEGER: {
                    VMStorage storage =
                        storageCalculator.nextStorage(StorageType.INTEGER, layout);
                    bindings.vmStore(storage, carrier);
                    break;
                }
                case FLOAT: {
                    VMStorage storage =
                        storageCalculator.nextStorage(StorageType.VECTOR, layout);
                    bindings.vmStore(storage, carrier);
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Unhandled class " + argumentClass);
            }
            return bindings.build();
        }
    }

    class BoxBindingCalculator extends BindingCalculator {
        BoxBindingCalculator(boolean forArguments) {
            super(forArguments, false);
        }

        @Override
        List<Binding> getIndirectBindings() {
            return Binding.builder()
                .vmLoad(INDIRECT_RESULT, long.class)
                .boxAddressRaw(Long.MAX_VALUE)
                .build();
        }

        @Override
        List<Binding> getBindings(Class<?> carrier, MemoryLayout layout) {
            TypeClass argumentClass = TypeClass.classifyLayout(layout);
            Binding.Builder bindings = Binding.builder();
            switch (argumentClass) {
                case STRUCT_REGISTER -> {
                    assert carrier == MemorySegment.class;
                    bindings.allocate(layout);
                    VMStorage[] regs = storageCalculator.regAlloc(
                            StorageType.INTEGER, layout);
                    if (regs != null) {
                        int regIndex = 0;
                        long offset = 0;
                        while (offset < layout.byteSize()) {
                            final long copy = Math.min(layout.byteSize() - offset, 8);
                            VMStorage storage = regs[regIndex++];
                            bindings.dup();
                            boolean useFloat = storage.type() == StorageType.VECTOR;
                            Class<?> type = SharedUtils.primitiveCarrierForSize(copy, useFloat);
                            bindings.vmLoad(storage, type)
                                    .bufferStore(offset, type);
                            offset += copy;
                        }
                    } else {
                        spillStructBox(bindings, layout);
                    }
                }
                case STRUCT_REFERENCE -> {
                    assert carrier == MemorySegment.class;
                    VMStorage storage = storageCalculator.nextStorage(
                            StorageType.INTEGER, AArch64.C_POINTER);
                    bindings.vmLoad(storage, long.class)
                            .boxAddress(layout);
                }
                case STRUCT_HFA -> {
                    assert carrier == MemorySegment.class;
                    bindings.allocate(layout);
                    GroupLayout group = (GroupLayout) layout;
                    VMStorage[] regs = storageCalculator.nextStorageForHFA(group);
                    if (regs != null) {
                        long offset = 0;
                        for (int i = 0; i < group.memberLayouts().size(); i++) {
                            VMStorage storage = regs[i];
                            final long size = group.memberLayouts().get(i).byteSize();
                            boolean useFloat = storage.type() == StorageType.VECTOR;
                            Class<?> type = SharedUtils.primitiveCarrierForSize(size, useFloat);
                            bindings.dup()
                                    .vmLoad(storage, type)
                                    .bufferStore(offset, type);
                            offset += size;
                        }
                    } else {
                        spillStructBox(bindings, layout);
                    }
                }
                case POINTER -> {
                    VMStorage storage =
                            storageCalculator.nextStorage(StorageType.INTEGER, layout);
                    bindings.vmLoad(storage, long.class)
                            .boxAddressRaw(Utils.pointeeSize(layout));
                }
                case INTEGER -> {
                    VMStorage storage =
                            storageCalculator.nextStorage(StorageType.INTEGER, layout);
                    bindings.vmLoad(storage, carrier);
                }
                case FLOAT -> {
                    VMStorage storage =
                            storageCalculator.nextStorage(StorageType.VECTOR, layout);
                    bindings.vmLoad(storage, carrier);
                }
                default -> throw new UnsupportedOperationException("Unhandled class " + argumentClass);
            }
            return bindings.build();
        }
    }
}
