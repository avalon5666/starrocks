// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.jni.connector;

import com.starrocks.utils.Platform;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reference to Apache Spark with some customization
 * see https://github.com/apache/spark/blob/master/sql/core/src/main/java/org/apache/spark/sql/execution/vectorized/WritableColumnVector.java
 */
public class OffHeapColumnVector {
    private long nulls;
    private long data;

    // Only set if type is Array or Map.
    private long offsetData;

    private int capacity;

    private ColumnType type;

    /**
     * Upper limit for the maximum capacity for this column.
     */
    // Some JVMs can't allocate arrays of length Integer.MAX_VALUE; actual max is somewhat smaller.
    // Be conservative and lower the cap a little.
    // Refer to "http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/tip/src/share/classes/java/util/ArrayList.java#l229"
    // This value is word rounded. Use this value if the allocated byte arrays are used to store other
    // types rather than bytes.
    private static final int MAX_CAPACITY = Integer.MAX_VALUE - 15;

    /**
     * Number of nulls in this column. This is an optimization for the reader, to skip NULL checks.
     */
    private int numNulls;

    private static final int DEFAULT_STRING_LENGTH = 4;

    /**
     * Current write cursor (row index) when appending data.
     */
    protected int elementsAppended;

    private OffHeapColumnVector[] childColumns;

    // Only for test，record the size of the NULL indicator
    private int nullsLength = 0;

    public OffHeapColumnVector(int capacity, ColumnType type) {
        this.capacity = capacity;
        this.type = type;
        this.nulls = 0;
        this.data = 0;
        this.offsetData = 0;

        reserveInternal(capacity);
        reset();
    }

    public long nullsNativeAddress() {
        return nulls;
    }

    public long valuesNativeAddress() {
        return data;
    }

    public long arrayOffsetNativeAddress() {
        return offsetData;
    }

    public long arrayDataNativeAddress() {
        return arrayData().valuesNativeAddress();
    }

    public void close() {
        if (childColumns != null) {
            for (int i = 0; i < childColumns.length; i++) {
                childColumns[i].close();
                childColumns[i] = null;
            }
            childColumns = null;
        }
        Platform.freeMemory(nulls);
        Platform.freeMemory(data);
        Platform.freeMemory(offsetData);
        nulls = 0;
        data = 0;
        offsetData = 0;
    }

    private void throwUnsupportedException(int requiredCapacity, Throwable cause) {
        String message = "Cannot reserve additional contiguous bytes in the vectorized vector (" +
                (requiredCapacity >= 0 ? "requested " + requiredCapacity + " bytes" : "integer overflow).");
        throw new RuntimeException(message, cause);
    }

    private void reserve(int requiredCapacity) {
        if (requiredCapacity < 0) {
            throwUnsupportedException(requiredCapacity, null);
        } else if (requiredCapacity > capacity) {
            int newCapacity = (int) Math.min(MAX_CAPACITY, requiredCapacity * 2L);
            if (requiredCapacity <= newCapacity) {
                try {
                    reserveInternal(newCapacity);
                } catch (OutOfMemoryError outOfMemoryError) {
                    throwUnsupportedException(requiredCapacity, outOfMemoryError);
                }
            } else {
                throwUnsupportedException(requiredCapacity, null);
            }
        }
    }

    private void reserveInternal(int newCapacity) {
        int oldCapacity = (nulls == 0L) ? 0 : capacity;
        long oldOffsetSize = (nulls == 0) ? 0 : (capacity + 1) * 4L;
        long newOffsetSize = (newCapacity + 1) * 4L;
        int typeSize = type.getPrimitiveTypeValueSize();
        if (type.isUnknown()) {
            // don't do anything.
        } else if (typeSize != -1) {
            this.data = Platform.reallocateMemory(data, oldCapacity * typeSize, newCapacity * typeSize);
        } else if (type.isByteStorageType()) {
            this.offsetData = Platform.reallocateMemory(offsetData, oldOffsetSize, newOffsetSize);
            // Just create a new object at the first time, otherwise the data will be lost during expansion,
            // and because the OFFSET record is continuous, the new offset address starts from 0 during the 
            // expansion, which will cause the offset records to be negatively numbered. After being passed
            // to BE, it becomes a non-sign number. This is a very huge number. When processing, the array 
            // will cross the boundary, which may cause crash.
            //
            // The child's capacity is not expanded here because the child's appendValue function is used 
            // to add data to it will automatically expand.
            if (this.childColumns == null) {
                int childCapacity = newCapacity * DEFAULT_STRING_LENGTH;
                this.childColumns = new OffHeapColumnVector[1];
                this.childColumns[0] = new OffHeapColumnVector(childCapacity, new ColumnType(type.name + "#data",
                        ColumnType.TypeValue.BYTE));
            }
        } else if (type.isArray() || type.isMap() || type.isStruct()) {
            if (type.isArray() || type.isMap()) {
                this.offsetData = Platform.reallocateMemory(offsetData, oldOffsetSize, newOffsetSize);
            }
            // Same as the above
            if (this.childColumns == null) {
                int size = type.childTypes.size();
                this.childColumns = new OffHeapColumnVector[size];
                for (int i = 0; i < size; i++) {
                    this.childColumns[i] = new OffHeapColumnVector(newCapacity, type.childTypes.get(i));
                }
            }
        } else {
            throw new RuntimeException("Unhandled type: " + type);
        }
        this.nulls = Platform.reallocateMemory(nulls, oldCapacity, newCapacity);
        Platform.setMemory(nulls + oldCapacity, (byte) 0, newCapacity - oldCapacity);
        capacity = newCapacity;
        this.nullsLength = capacity;
        if (offsetData != 0) {
            // offsetData[0] == 0 always.
            // we have to set it explicitly otherwise it's undefined value here.
            Platform.putInt(null, offsetData, 0);
        }
    }

    private void reset() {
        if (childColumns != null) {
            for (OffHeapColumnVector c : childColumns) {
                c.reset();
            }
        }
        elementsAppended = 0;
        if (numNulls > 0) {
            putNotNulls(0, capacity);
            numNulls = 0;
        }
    }

    private OffHeapColumnVector arrayData() {
        return childColumns[0];
    }

    public boolean isNullAt(int rowId) {
        return Platform.getByte(null, nulls + rowId) == 1;
    }

    public boolean hasNull() {
        return numNulls > 0;
    }

    private void putNotNulls(int rowId, int count) {
        if (!hasNull()) {
            return;
        }
        long offset = nulls + rowId;
        for (int i = 0; i < count; ++i, ++offset) {
            Platform.putByte(null, offset, (byte) 0);
        }
    }

    public int appendNull() {
        reserve(elementsAppended + 1);
        putNull(elementsAppended);

        if (offsetData != 0) {
            int offset = getArrayOffset(elementsAppended);
            putArrayOffset(elementsAppended, offset, 0);
        }

        if (type.isStruct()) {
            for (int i = 0; i < childColumns.length; i++) {
                childColumns[i].appendValue(null);
            }
        }

        return elementsAppended++;
    }

    private void putNull(int rowId) {
        Platform.putByte(null, nulls + rowId, (byte) 1);
        ++numNulls;
    }

    public int appendByte(byte v) {
        reserve(elementsAppended + 1);
        putByte(elementsAppended, v);
        return elementsAppended++;
    }

    public void putByte(int rowId, byte value) {
        Platform.putByte(null, data + rowId, value);
    }

    public byte getByte(int rowId) {
        return Platform.getByte(null, data + rowId);
    }

    public int appendBoolean(boolean v) {
        reserve(elementsAppended + 1);
        putBoolean(elementsAppended, v);
        return elementsAppended++;
    }

    private void putBoolean(int rowId, boolean value) {
        Platform.putByte(null, data + rowId, (byte) ((value) ? 1 : 0));
    }

    public boolean getBoolean(int rowId) {
        return Platform.getByte(null, data + rowId) == 1;
    }

    public int appendShort(short v) {
        reserve(elementsAppended + 1);
        putShort(elementsAppended, v);
        return elementsAppended++;
    }

    private void putShort(int rowId, short value) {
        Platform.putShort(null, data + 2L * rowId, value);
    }

    public short getShort(int rowId) {
        return Platform.getShort(null, data + 2L * rowId);
    }

    public int appendInt(int v) {
        reserve(elementsAppended + 1);
        putInt(elementsAppended, v);
        return elementsAppended++;
    }

    private void putInt(int rowId, int value) {
        Platform.putInt(null, data + 4L * rowId, value);
    }

    public int getInt(int rowId) {
        return Platform.getInt(null, data + 4L * rowId);
    }

    public int appendFloat(float v) {
        reserve(elementsAppended + 1);
        putFloat(elementsAppended, v);
        return elementsAppended++;
    }

    private void putFloat(int rowId, float value) {
        Platform.putFloat(null, data + rowId * 4L, value);
    }

    public float getFloat(int rowId) {
        return Platform.getFloat(null, data + rowId * 4L);
    }

    public int appendLong(long v) {
        reserve(elementsAppended + 1);
        putLong(elementsAppended, v);
        return elementsAppended++;
    }

    private void putLong(int rowId, long value) {
        Platform.putLong(null, data + 8L * rowId, value);
    }

    public long getLong(int rowId) {
        return Platform.getLong(null, data + 8L * rowId);
    }

    public int appendDouble(double v) {
        reserve(elementsAppended + 1);
        putDouble(elementsAppended, v);
        return elementsAppended++;
    }

    public int appendTime(int v) {
        reserve(elementsAppended + 1);
        putDouble(elementsAppended, (double) v / 1000);
        return elementsAppended++;
    }

    private void putDouble(int rowId, double value) {
        Platform.putDouble(null, data + rowId * 8L, value);
    }

    public double getDouble(int rowId) {
        return Platform.getDouble(null, data + rowId * 8L);
    }

    public int appendDecimal(BigDecimal value) {
        reserve(elementsAppended + 1);
        putDecimal(elementsAppended, value);
        return elementsAppended++;
    }

    private void putDecimal(int rowId, BigDecimal value) {
        int typeSize = type.getPrimitiveTypeValueSize();
        BigInteger dataValue = value.setScale(type.getScale(), RoundingMode.UNNECESSARY).unscaledValue();
        byte[] bytes = changeByteOrder(dataValue.toByteArray());
        byte[] newValue = new byte[typeSize];
        if (dataValue.signum() == -1) {
            Arrays.fill(newValue, (byte) -1);
        }
        System.arraycopy(bytes, 0, newValue, 0, Math.min(bytes.length, newValue.length));
        Platform.copyMemory(newValue, Platform.BYTE_ARRAY_OFFSET, null, data + rowId * typeSize, typeSize);
    }

    public BigDecimal getDecimal(int rowId) {
        int typeSize = type.getPrimitiveTypeValueSize();
        byte[] bytes = new byte[typeSize];
        Platform.copyMemory(null, data + (long) rowId * typeSize, bytes, Platform.BYTE_ARRAY_OFFSET, typeSize);
        BigInteger value = new BigInteger(changeByteOrder(bytes));
        return new BigDecimal(value, type.getScale());
    }

    private void putBytes(int rowId, int count, byte[] src, int srcIndex) {
        Platform.copyMemory(src, Platform.BYTE_ARRAY_OFFSET + srcIndex, null, data + rowId, count);
    }

    private byte[] getBytes(int rowId, int count) {
        byte[] array = new byte[count];
        Platform.copyMemory(null, data + rowId, array, Platform.BYTE_ARRAY_OFFSET, count);
        return array;
    }

    private int appendBytes(int length, byte[] src, int offset) {
        reserve(elementsAppended + length);
        int result = elementsAppended;
        putBytes(elementsAppended, length, src, offset);
        elementsAppended += length;
        return result;
    }

    public int appendString(String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        return appendByteArray(bytes, 0, bytes.length);
    }

    public int appendBinary(byte[] binary) {
        return appendByteArray(binary, 0, binary.length);
    }

    private int appendByteArray(byte[] value, int offset, int length) {
        int copiedOffset = arrayData().appendBytes(length, value, offset);
        reserve(elementsAppended + 1);
        putArrayOffset(elementsAppended, copiedOffset, length);
        return elementsAppended++;
    }

    private void putArrayOffset(int rowId, int offset, int length) {
        Platform.putInt(null, offsetData + 4L * rowId, offset);
        Platform.putInt(null, offsetData + 4L * (rowId + 1), offset + length);
    }

    public String getUTF8String(int rowId) {
        if (isNullAt(rowId)) {
            return null;
        }
        int start = getArrayOffset(rowId);
        int end = getArrayOffset(rowId + 1);
        int size = end - start;
        byte[] bytes = arrayData().getBytes(start, size);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private int getArrayOffset(int rowId) {
        return Platform.getInt(null, offsetData + 4L * rowId);
    }

    private int getArraySize(int rowId) {
        return getArrayOffset(rowId + 1) - getArrayOffset(rowId);
    }

    private int appendArray(List<ColumnValue> values) {
        int size = values.size();
        int offset = childColumns[0].elementsAppended;
        for (ColumnValue v : values) {
            childColumns[0].appendValue(v);
        }
        reserve(elementsAppended + 1);
        putArrayOffset(elementsAppended, offset, size);
        return elementsAppended++;
    }

    private int appendMap(List<ColumnValue> keys, List<ColumnValue> values) {
        int size = keys.size();
        int offset = childColumns[0].elementsAppended;
        int idx = 0;
        if (type.isMapKeySelected()) {
            for (ColumnValue k : keys) {
                childColumns[idx].appendValue(k);
            }
            idx += 1;
        }
        if (type.isMapValueSelected()) {
            for (ColumnValue v : values) {
                childColumns[idx].appendValue(v);
            }
            idx += 1;
        }
        reserve(elementsAppended + 1);
        putArrayOffset(elementsAppended, offset, size);
        return elementsAppended++;
    }

    private int appendStruct(List<ColumnValue> values) {
        for (int i = 0; i < childColumns.length; i++) {
            childColumns[i].appendValue(values.get(i));
        }
        // for nulls indicator
        reserve(elementsAppended + 1);
        return elementsAppended++;
    }

    public int appendDate(LocalDate v) {
        reserve(elementsAppended + 1);
        int date = convertToDate(v.getYear(), v.getMonthValue(), v.getDayOfMonth());
        return appendInt(date);
    }

    public int appendDateTime(LocalDateTime v) {
        reserve(elementsAppended + 1);
        long datetime = convertToDateTime(v.getYear(), v.getMonthValue(), v.getDayOfMonth(), v.getHour(),
                v.getMinute(), v.getSecond(), v.getNano() / 1000);
        return appendLong(datetime);
    }

    public void updateMeta(OffHeapColumnVector meta) {
        if (type.isUnknown()) {
            meta.appendLong(0);
        } else if (type.isByteStorageType()) {
            meta.appendLong(nullsNativeAddress());
            meta.appendLong(arrayOffsetNativeAddress());
            meta.appendLong(arrayDataNativeAddress());
        } else if (type.isArray() || type.isMap() || type.isStruct()) {
            meta.appendLong(nullsNativeAddress());
            if (type.isArray() || type.isMap()) {
                meta.appendLong(arrayOffsetNativeAddress());
            }
            for (OffHeapColumnVector c : childColumns) {
                c.updateMeta(meta);
            }
        } else {
            meta.appendLong(nullsNativeAddress());
            meta.appendLong(valuesNativeAddress());
        }
    }

    public void appendValue(ColumnValue o) {
        ColumnType.TypeValue typeValue = type.getTypeValue();
        if (o == null) {
            appendNull();
            return;
        }

        switch (typeValue) {
            case TINYINT:
                appendByte(o.getByte());
                break;
            case BOOLEAN:
                appendBoolean(o.getBoolean());
                break;
            case SHORT:
                appendShort(o.getShort());
                break;
            case INT:
                appendInt(o.getInt());
                break;
            case FLOAT:
                appendFloat(o.getFloat());
                break;
            case LONG:
                appendLong(o.getLong());
                break;
            case DOUBLE:
                appendDouble(o.getDouble());
                break;
            case TIME:
                appendTime(o.getInt());
                break;
            case BINARY:
                appendBinary(o.getBytes());
                break;
            case STRING:
                appendString(o.getString(typeValue));
                break;
            case DATE:
                appendDate(o.getDate());
                break;
            case DECIMALV2:
            case DECIMAL32:
            case DECIMAL64:
            case DECIMAL128:
                appendDecimal(o.getDecimal());
                break;
            case DATETIME:
            case DATETIME_MICROS:
            case DATETIME_MILLIS:
                appendDateTime(o.getDateTime(typeValue));
                break;
            case ARRAY: {
                List<ColumnValue> values = new ArrayList<>();
                o.unpackArray(values);
                appendArray(values);
                break;
            }
            case MAP: {
                List<ColumnValue> keys = new ArrayList<>();
                List<ColumnValue> values = new ArrayList<>();
                o.unpackMap(keys, values);
                appendMap(keys, values);
                break;
            }
            case STRUCT: {
                List<ColumnValue> values = new ArrayList<>();
                o.unpackStruct(type.getFieldIndex(), values);
                appendStruct(values);
                break;
            }
            default:
                throw new RuntimeException("Unknown type value: " + typeValue);
        }
    }

    OffHeapColumnVector getMapKeyColumnVector() {
        if (type.isMapKeySelected()) {
            return childColumns[0];
        } else {
            return null;
        }
    }

    OffHeapColumnVector getMapValueColumnVector() {
        if (type.isMapValueSelected()) {
            if (type.isMapKeySelected()) {
                return childColumns[1];
            } else {
                return childColumns[0];
            }
        }
        return null;
    }

    // for test only.
    public void dump(StringBuilder sb, int i) {
        if (isNullAt(i)) {
            sb.append("NULL");
            return;
        }

        ColumnType.TypeValue typeValue = type.getTypeValue();
        switch (typeValue) {
            case TINYINT:
                sb.append(getByte(i));
                break;
            case BOOLEAN:
                sb.append(getBoolean(i));
                break;
            case SHORT:
                sb.append(getShort(i));
                break;
            case INT:
                sb.append(getInt(i));
                break;
            case FLOAT:
                sb.append(getFloat(i));
                break;
            case LONG:
                sb.append(getLong(i));
                break;
            case DOUBLE:
                sb.append(getDouble(i));
                break;
            case BINARY:
                sb.append("<binary>");
                break;
            case STRING:
                sb.append(getUTF8String(i));
                break;
            case DATETIME:
            case DATETIME_MICROS:
            case DATETIME_MILLIS:
                // using long
                sb.append(getLong(i));
                break;
            case DECIMALV2:
            case DECIMAL32:
            case DECIMAL64:
            case DECIMAL128:
                sb.append(getDecimal(i));
                break;
            case ARRAY: {
                int begin = getArrayOffset(i);
                int end = getArrayOffset(i + 1);
                sb.append("[");
                for (int rowId = begin; rowId < end; rowId++) {
                    if (rowId != begin) {
                        sb.append(',');
                    }
                    childColumns[0].dump(sb, rowId);
                }
                sb.append("]");
                break;
            }
            case MAP: {
                int begin = getArrayOffset(i);
                int end = getArrayOffset(i + 1);
                sb.append("[");
                OffHeapColumnVector key = getMapKeyColumnVector();
                OffHeapColumnVector value = getMapValueColumnVector();
                for (int rowId = begin; rowId < end; rowId++) {
                    if (rowId != begin) {
                        sb.append(",");
                    }
                    sb.append("{");
                    if (key != null) {
                        key.dump(sb, rowId);
                    } else {
                        sb.append("null");
                    }
                    sb.append(":");
                    if (value != null) {
                        value.dump(sb, rowId);
                    } else {
                        sb.append("null");
                    }
                    sb.append("}");
                }
                sb.append("]");
                break;
            }
            case STRUCT: {
                List<String> names = type.getChildNames();
                sb.append("{");
                for (int c = 0; c < names.size(); c++) {
                    if (c != 0) {
                        sb.append(",");
                    }
                    sb.append(names.get(c)).append(":");
                    childColumns[c].dump(sb, i);
                }
                sb.append("}");
                break;
            }
            default:
                throw new RuntimeException("Unknown type value: " + typeValue);
        }
        return;
    }

    // for test only.
    public void checkMeta(OffHeapTable.MetaChecker checker) {
        String context = type.name;
        if (type.isUnknown()) {
            checker.check(context + "#unknown", 0);
            return;
        }
        checker.check(context + "#null", nulls);
        String contextOffset = context + "#offset";
        if (type.isByteStorageType()) {
            checker.check(contextOffset, arrayOffsetNativeAddress());
            checker.check(context + "#data", arrayDataNativeAddress());
        } else if (type.isArray() || type.isMap() || type.isStruct()) {
            if (type.isArray() || type.isMap()) {
                checker.check(contextOffset, arrayOffsetNativeAddress());
            }
            for (OffHeapColumnVector c : childColumns) {
                c.checkMeta(checker);
                if (type.isStruct()) {
                    // For example
                    // struct<a: null>
                    // struct<a: null>
                    // struct<a: null>
                    // numNulls for struct level = 0
                    // c.numNulls for a level = 3
                    // numNulls must always <= c.numNulls
                    if (numNulls <= c.numNulls && elementsAppended != c.elementsAppended) {
                        throw new RuntimeException(
                                "struct type check failed, root numNulls=" + numNulls + ", elementsAppended=" +
                                elementsAppended + "; however, child " + c.type.name + " numNulls=" + c.numNulls +
                                ", elementsAppended=" + c.elementsAppended);
                    }
                }
            }
        } else {
            checker.check(context + "#data", data);
        }
    }

    public byte[] changeByteOrder(byte[] bytes) {
        int length = bytes.length;
        for (int i = 0; i < length / 2; ++i) {
            byte temp = bytes[i];
            bytes[i] = bytes[length - 1 - i];
            bytes[length - 1 - i] = temp;
        }
        return bytes;
    }

    /**
     * logical components in be: time_types.cpp, date::from_date
     */
    private int convertToDate(int year, int month, int day) {
        int century;
        int julianDate;

        if (month > 2) {
            month += 1;
            year += 4800;
        } else {
            month += 13;
            year += 4799;
        }
        century = year / 100;
        julianDate = year * 365 - 32167;
        julianDate += year / 4 - century + century / 4;
        julianDate += 7834 * month / 256 + day;

        return julianDate;
    }

    /**
     * logical components in be: time_types.h, timestamp::from_datetime
     */
    private long convertToDateTime(int year, int month, int day, int hour, int minute, int second, int microsecond) {
        int secsPerMinute = 60;
        int minsPerHour = 60;
        long usecsPerSec = 1000000;
        int timeStampBits = 40;
        long julianDate = convertToDate(year, month, day);
        long timestamp = (((((hour * minsPerHour) + minute) * secsPerMinute) + second) * usecsPerSec)
                + microsecond;
        return julianDate << timeStampBits | timestamp;
    }

    // for test only
    public boolean checkNullsLength() {
        ColumnType.TypeValue typeValue = type.getTypeValue();
        switch (typeValue) {
            case TINYINT:
            case BOOLEAN:
            case SHORT:
            case INT:
            case FLOAT:
            case LONG:
            case DOUBLE:
            case DECIMALV2:
            case DECIMAL32:
            case DECIMAL64:
            case DECIMAL128:
                return nullsLength >= elementsAppended;
            case BINARY:
            case STRING:
            case DATE:
            case DATETIME:
            case DATETIME_MICROS:
            case DATETIME_MILLIS:
            case ARRAY:
                return nullsLength >= elementsAppended && childColumns[0].checkNullsLength();
            case MAP:
                return nullsLength >= elementsAppended && childColumns[0].checkNullsLength()
                        && childColumns[1].checkNullsLength();
            case STRUCT: {
                List<String> names = type.getChildNames();
                for (int c = 0; c < names.size(); c++) {
                    if (!childColumns[c].checkNullsLength()) {
                        return false;
                    }
                }
                return nullsLength >= elementsAppended;
            }
            default:
                throw new RuntimeException("Unknown type value: " + typeValue);
        }
    }
}