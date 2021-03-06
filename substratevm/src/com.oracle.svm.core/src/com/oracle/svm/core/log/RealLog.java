/*
 * Copyright (c) 2015, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

package com.oracle.svm.core.log;

import java.io.FileDescriptor;

import org.graalvm.compiler.core.common.calc.UnsignedMath;
import org.graalvm.compiler.word.Word;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordBase;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.annotate.RestrictHeapAccess;
import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.jdk.UninterruptibleUtils;
import com.oracle.svm.core.jdk.UninterruptibleUtils.Integer;
import com.oracle.svm.core.jdk.UninterruptibleUtils.Math;
import com.oracle.svm.core.snippets.KnownIntrinsics;
import com.oracle.svm.core.util.VMError;

public class RealLog extends Log {
    private boolean autoflush = false;
    private int indent = 0;

    protected RealLog() {
        super();
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public boolean isEnabled() {
        return true;
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log string(String value) {
        if (value != null) {
            rawString(value);
        } else {
            rawString("null");
        }
        return this;
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log string(String str, int fill, int align) {

        int spaces = fill - SubstrateUtil.getRawStringChars(str).length;

        if (align == RIGHT_ALIGN) {
            spaces(spaces);
        }

        string(str);

        if (align == LEFT_ALIGN) {
            spaces(spaces);
        }

        return this;
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log string(char[] value) {
        if (value != null) {
            rawString(value);
        } else {
            rawString("null");
        }
        return this;
    }

    @Uninterruptible(reason = "Returns raw pointer into an array.", callerMustBe = true)
    private static CCharPointer arrayBase(Object array) {
        return (CCharPointer) Word.objectToUntrackedPointer(array).add(LayoutEncoding.getArrayBaseOffset(KnownIntrinsics.readHub(array).getLayoutEncoding()));
    }

    @Uninterruptible(reason = "Uses raw pointer into an array.")
    @Override
    public Log string(byte[] value, int offset, int length) {
        if (value == null) {
            rawString("null");
        } else if ((offset < 0) || (offset > value.length) || (length < 0) || ((offset + length) > value.length) || ((offset + length) < 0)) {
            rawString("OUT OF BOUNDS");
        } else {
            /*
             * We pass out a pointer into the array, without any pinning, so that we stay guaranteed
             * allocation-free.
             */
            rawBytes(arrayBase(value).addressOf(offset), WordFactory.unsigned(length));
        }
        return this;
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log string(CCharPointer value) {
        if (value.notEqual(WordFactory.nullPointer())) {
            rawBytes(value, SubstrateUtil.strlen(value));
        } else {
            rawString("null");
        }
        return this;
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log character(char value) {
        CCharPointer bytes = StackValue.get(SizeOf.get(CCharPointer.class));
        bytes.write((byte) value);
        rawBytes(bytes, WordFactory.unsigned(1));
        return this;
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log newline() {
        character('\n');
        if (autoflush) {
            flush();
        }
        spaces(indent);
        return this;
    }

    /**
     * Prints the value according according to the given format specification. The digits '0' to '9'
     * followed by the letters 'a' to 'z' are used to represent the digits.
     *
     * @param value The value to print.
     * @param radix The base of the value, between 2 and 36.
     * @param signed true if the value should be treated as a signed value (and the digits are
     *            preceded by '-' for negative values).
     */
    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log number(long value, int radix, boolean signed) {
        return number(value, radix, signed, 0, NO_ALIGN);
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    private Log number(long value, int radix, boolean signed, int fill, int align) {
        if (radix < 2 || radix > 36) {
            /* Ignore bogus parameter value. */
            return this;
        }

        /* Enough space for 64 digits in binary format, and the '-' for a negative value. */
        final int chunkSize = Long.SIZE + 1;
        CCharPointer bytes = StackValue.get(chunkSize, SizeOf.get(CCharPointer.class));
        int charPos = chunkSize;

        boolean negative = signed && value < 0;
        long curValue;
        if (negative) {
            /*
             * We do not have to worry about the overflow of Long.MIN_VALUE here, since we treat
             * curValue as an unsigned value.
             */
            curValue = -value;
        } else {
            curValue = value;
        }

        while (UnsignedMath.aboveOrEqual(curValue, radix)) {
            charPos--;
            bytes.write(charPos, digit(Long.remainderUnsigned(curValue, radix)));
            curValue = Long.divideUnsigned(curValue, radix);
        }
        charPos--;
        bytes.write(charPos, digit(curValue));

        if (negative) {
            charPos--;
            bytes.write(charPos, (byte) '-');
        }

        int length = chunkSize - charPos;

        if (align == RIGHT_ALIGN) {
            int spaces = fill - length;
            spaces(spaces);
        }

        rawBytes(bytes.addressOf(charPos), WordFactory.unsigned(length));

        if (align == LEFT_ALIGN) {
            int spaces = fill - length;
            spaces(spaces);
        }

        return this;
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log signed(WordBase value) {
        return number(value.rawValue(), 10, true);
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log signed(int value) {
        return number(value, 10, true);
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log signed(long value) {
        return number(value, 10, true);
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log unsigned(WordBase value) {
        return number(value.rawValue(), 10, false);
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log unsigned(WordBase value, int fill, int align) {
        return number(value.rawValue(), 10, false, fill, align);
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log unsigned(int value) {
        // unsigned expansion from int to long
        return number(value & 0xffffffffL, 10, false);
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log unsigned(long value) {
        return number(value, 10, false);
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log unsigned(long value, int fill, int align) {
        return number(value, 10, false, fill, align);
    }

    /**
     * Fast printing of a rational numbers without allocation memory.
     *
     * <p>
     * Note: this method will not perform rounding.
     * </p>
     * <p>
     * Note: this method will print all trailing zeros, i.e., {@code rational(1, 2, 4)} prints
     * {@code 0.5000}
     * </p>
     *
     * @param numerator Numerator in division
     * @param denominator or divisor
     * @param decimals number of decimals after the . to be printed. Note that no rounding is
     *            performed and trailing zeros are printed.
     */
    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log rational(long numerator, long denominator, long decimals) {
        if (denominator == 0) {
            throw VMError.shouldNotReachHere("Division by zero");
        }
        if (decimals < 0) {
            throw VMError.shouldNotReachHere("Number of decimals smaller than 0");
        }

        long value = numerator / denominator;
        unsigned(value);
        if (decimals > 0) {
            character('.');

            long positiveNumerator = Math.abs(numerator);
            long positiveDenominator = Math.abs(denominator);

            long remainder = positiveNumerator % positiveDenominator;
            for (int i = 0; i < decimals; i++) {
                remainder *= 10;
                unsigned(remainder / positiveDenominator);
                remainder = remainder % positiveDenominator;
            }
        }
        return this;
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log hex(WordBase value) {
        return string("0x").number(value.rawValue(), 16, false);
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log hex(int value) {
        return string("0x").number(value & 0xffffffffL, 16, false);
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log hex(long value) {
        return string("0x").number(value, 16, false);
    }

    private static final byte[] trueString = Boolean.TRUE.toString().getBytes();
    private static final byte[] falseString = Boolean.FALSE.toString().getBytes();

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log bool(boolean value) {
        return string(value ? trueString : falseString);
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log object(Object value) {
        return (value == null ? string("null") : string(value.getClass().getName()).string("@").hex(Word.objectToUntrackedPointer(value)));
    }

    private static final char spaceChar = ' ';

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log spaces(int value) {
        for (int i = 0; i < value; i += 1) {
            character(spaceChar);
        }
        return this;
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log flush() {
        ConfigurationValues.getOSInterface().flushUninterruptibly(getOutputFile());
        /* ignore error -- they're benign */
        return this;
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log autoflush(boolean onOrOff) {
        autoflush = onOrOff;
        return this;
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log indent(boolean addOrRemove) {
        int delta = addOrRemove ? 2 : -2;
        indent = UninterruptibleUtils.Math.max(0, indent + delta);
        return newline();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.")
    private static byte digit(long d) {
        return (byte) (d + (d < 10 ? '0' : 'a' - 10));
    }

    @RestrictHeapAccess(access = RestrictHeapAccess.Access.UNRESTRICTED, overridesCallers = true, reason = "Some implementations allocate.")
    @Uninterruptible(reason = "bytes can be a raw pointer into an array.", calleeMustBe = false)
    protected Log rawBytes(CCharPointer bytes, UnsignedWord length) {
        if (!ConfigurationValues.getOSInterface().writeBytesUninterruptibly(getOutputFile(), bytes, length)) {
            /*
             * We are in a low-level log routine and output failed, so there is little we can do.
             */
            ConfigurationValues.getOSInterface().abort();
        }
        return this;
    }

    /* Allow subclasses to customize the file descriptor that we write to. */
    @Uninterruptible(reason = "Called from uninterruptible code.")
    protected FileDescriptor getOutputFile() {
        return FileDescriptor.err;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.")
    private void rawString(String value) {
        rawString(SubstrateUtil.getRawStringChars(value));
    }

    @Uninterruptible(reason = "Called from uninterruptible code.")
    private void rawString(char[] value) {
        int length = value.length;

        /*
         * Stack allocation needs an allocation size that is a compile time constant, so we split
         * the string up in multiple chunks and write them separately.
         */
        final int chunkSize = 256;
        final CCharPointer bytes = StackValue.get(chunkSize);

        int chunkOffset = 0;
        while (length > 0) {
            int chunkLength = UninterruptibleUtils.Math.min(length, chunkSize);

            for (int i = 0; i < chunkLength; i++) {
                char c = value[chunkOffset + i];
                bytes.write(i, (byte) c);
            }
            rawBytes(bytes, WordFactory.unsigned(chunkLength));

            chunkOffset += chunkLength;
            length -= chunkLength;
        }
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log zhex(long value) {
        int zeros = UninterruptibleUtils.Long.numberOfLeadingZeros(value);
        int hexZeros = zeros / 4;
        for (int i = 0; i < hexZeros; i += 1) {
            character('0');
        }
        if (value != 0) {
            number(value, 16, false);
        }
        return this;
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    private Log zhex(int value, int wordSizeInBytes) {
        int zeros = UninterruptibleUtils.Integer.numberOfLeadingZeros(value) - 32 + (wordSizeInBytes * 8);
        int hexZeros = zeros / 4;
        for (int i = 0; i < hexZeros; i += 1) {
            character('0');
        }
        if (value != 0) {
            number(value & 0xffffffffL, 16, false);
        }
        return this;
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log zhex(int value) {
        return zhex(value, 4);
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log zhex(short value) {
        int intValue = value;
        return zhex(intValue & 0xffff, 2);
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log zhex(byte value) {
        int intValue = value;
        return zhex(intValue & 0xff, 1);
    }

    @Uninterruptible(reason = "Logging may be called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Log hexdump(PointerBase from, int wordSize, int numWords) {
        Pointer base = WordFactory.pointer(from.rawValue());
        int sanitizedWordsize = wordSize > 0 ? Integer.highestOneBit(Math.min(wordSize, 8)) : 2;
        for (int offset = 0; offset < sanitizedWordsize * numWords; offset += sanitizedWordsize) {
            if (offset % 16 == 0) {
                zhex(base.add(offset).rawValue());
                string(":");
            }
            string(" ");
            switch (sanitizedWordsize) {
                case 1:
                    zhex(base.readByte(offset));
                    break;
                case 2:
                    zhex(base.readShort(offset));
                    break;
                case 4:
                    zhex(base.readInt(offset));
                    break;
                case 8:
                    zhex(base.readLong(offset));
                    break;
            }
            if ((offset + sanitizedWordsize) % 16 == 0) {
                newline();
            }
        }
        return this;
    }
}
