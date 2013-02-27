/*
 * This project is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This project is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this project. If not, see <http://www.gnu.org/licenses/>.
 */
package com.cinchapi.concourse.db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import javax.annotation.concurrent.Immutable;

import com.cinchapi.concourse.util.ByteBuffers;
import com.cinchapi.concourse.util.Numbers;
import com.cinchapi.util.Hash;
import com.cinchapi.util.ObjectReuseCache;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.primitives.Longs;

/**
 * <p>
 * An immutable typed quantity that is {@link Locatable} using a
 * timestamp<sup>1</sup> and contained within a {@link Cell}. Both
 * <code>naturally</code> sortable in descending order by timestamp and
 * <code>logically</code> sortable in ascending order by quantity( regardless of
 * <code>type</code>). This is the most basic element of data in
 * {@link Concourse}. A single value cannot be larger than 2GB. <br>
 * <br>
 * <sup>1</sup> - No two values can have the same timestamp
 * </p>
 * <p>
 * <h2>Storage Requirements</h2>
 * Each value requires at least 48 bytes of space. Additional space requirements
 * are as follows:
 * <ul>
 * <li>BOOLEAN requires an additional 1 byte</li>
 * <li>DOUBLE requires an additional 8 bytes</li>
 * <li>FLOAT requires an additional 4 bytes</li>
 * <li>INTEGER requires an additional 4 bytes</li>
 * <li>LONG requires an additional 8 bytes</li>
 * <li>RELATION requires an additional 8 bytes</li>
 * <li>STRING requires an additional 1-4 bytes for every character (uses UTF-8)
 * encoding</li>
 * </ul>
 * </p>
 * 
 * @author jnelson
 * @since 1.0
 */
@Immutable
public final class Value implements
		Comparable<Value>,
		FileChannelPersistable,
		Locatable {

	/**
	 * Return the value represented by <code>bytes</code>. Use this method when
	 * reading and reconstructing from a file. This method assumes that
	 * <code>bytes</code> was generated using {@link #getBytes()}.
	 * 
	 * @param bytes
	 * @return the value
	 */
	public static Value fromByteSequence(ByteBuffer bytes) {
		long timestamp = bytes.getLong();
		Type type = Type.values()[bytes.getInt()];

		byte[] locator = new byte[32];
		bytes.get(locator);

		int size = bytes.getInt() - fixedSizeInBytes;

		byte[] qty = new byte[size];
		bytes.get(qty);
		ByteBuffer quantity = ByteBuffer.wrap(qty);
		quantity.rewind();

		return new Value(quantity, type, timestamp);
	}

	/**
	 * Return a value that is appropriate for storage.
	 * 
	 * @param quantity
	 * @param timestamp
	 * @return the new instance.
	 */
	public static Value forStorage(Object quantity, long timestamp) {
		return new Value(quantity, timestamp); // do not use cache because
												// forStorage values must have
												// a unique timestamp and will
												// thus never be duplicated
	}

	/**
	 * Return a value that is not appropriate for storage, but can be used in
	 * comparisons. This is the preferred way to create values unless the value
	 * will be stored.
	 * 
	 * @param quantity
	 * @return the new instance.
	 */
	public static Value notForStorage(Object quantity) {
		Value value = cache.get(quantity);
		if(value == null) {
			value = new Value(quantity);
			cache.put(value, quantity);
		}
		return value;
	}

	/**
	 * Read the next value from <code>channel</code> assuming that it conforms
	 * to the specification described in the {@link #asByteBuffer()} method.
	 * 
	 * @param buffer
	 * @return the next {@link Value} in the channel.
	 */
	public static Value readFrom(FileChannel channel) throws IOException {
		ByteBuffer buffers[] = new ByteBuffer[4];
		buffers[0] = ByteBuffer.allocate(8); // timestamp
		buffers[1] = ByteBuffer.allocate(4); // type
		buffers[2] = ByteBuffer.allocate(32); // locator
		buffers[3] = ByteBuffer.allocate(4); // size
		channel.read(buffers);

		for (ByteBuffer buf : buffers) {
			buf.rewind();
		}

		long timestamp = buffers[0].getLong();
		Type type = Type.values()[buffers[1].getInt()];
		int size = buffers[3].getInt() - fixedSizeInBytes;

		ByteBuffer quantity = ByteBuffer.allocate(size);
		channel.read(quantity);
		quantity.rewind();

		return new Value(quantity, type, timestamp);

	}

	/**
	 * Write the value to a writable <code>channel</code> and close it
	 * afterwards. This method will acquire a lock over the region from the
	 * channels current position plus the {@link #size()} of the value.
	 * 
	 * @param channel
	 * @param value
	 * @throws IOException
	 */
	public static void writeTo(FileChannel channel, Value value)
			throws IOException {
		value.writeTo(channel);
	}

	private static final ObjectReuseCache<Value> cache = new ObjectReuseCache<Value>();
	private static final int fixedSizeInBytes = (2 * (Integer.SIZE / 8))
			+ (Long.SIZE / 8) + 32;
	private static final int maxQuantitySizeInBytes = Integer.MAX_VALUE
			- fixedSizeInBytes; // Max size is limited to about 2GB
								// because size is stored
								// using a 4 byte signed integer
	private static final long nil = 0L;

	/**
	 * Every value requires a minimum of 48 bytes for storage.
	 */
	public static final int MIN_SIZE = fixedSizeInBytes;

	private final ByteBuffer quantity;
	private final Type type;
	private final long timestamp;
	private final byte[] locator; // SHA-256 hash (32 bytes)
	private final int size;

	/**
	 * Construct a new instance.
	 * 
	 * @param quantity
	 * @param type
	 * @param timestamp
	 */
	private Value(ByteBuffer quantity, Type type, long timestamp) {
		Preconditions.checkNotNull(quantity);
		Preconditions.checkNotNull(type);

		this.quantity = quantity;
		this.type = type;
		this.timestamp = timestamp;
		this.locator = Hash.sha256(getQuantityBuffer().array(), ByteBuffers
				.toByteBuffer(this.type.ordinal()).array());
		this.size = getQuantityBuffer().capacity() + fixedSizeInBytes;
	}

	/**
	 * <p>
	 * Construct a new <em>unstorable</em> instance for use with non-storing
	 * methods which typically use {@link #equals(Object)} or a
	 * <code>timestamp</code> ignoring {@link Comparator}.
	 * </p>
	 * <p>
	 * <strong>Note:</strong> The constructed object is <strong>not</strong>
	 * suitable for performing functions that add to the underlying datastore.
	 * </p>
	 * 
	 * @param quantity
	 */
	private Value(Object quantity) {
		this(quantity, nil);
	}

	/**
	 * Construct a new instance for use with storing methods that sort based on
	 * <code>timestamp</code>.
	 * 
	 * @param quantity
	 * @param timestamp
	 */
	private Value(Object quantity, long timestamp) {
		this(Utilities.getByteBufferForObject(quantity), Utilities
				.getObjectType(quantity), timestamp);
	}

	/**
	 * Natural comparison where the value with the larger timestamp is less than
	 * the other. This enables sorting by timestamp in descending order. This
	 * method correctly accounts for comparing a forStorage value to a
	 * notForStorage one.
	 */
	@Override
	public int compareTo(Value o) {
		return compareTo(o, false);
	}

	/**
	 * Determine if the comparison to <code>o</code> should be done naturally or
	 * <code>logically</code>.
	 * 
	 * @param o
	 * @param logically
	 *            if <code>true</code> the value based comparison occurs,
	 *            otherwise based on timestamp/equality
	 * @return a negative integer, zero, or a positive integer as this object is
	 *         less than, equal to, or greater than the specified object.
	 * @see {@link #compareTo(Value)}
	 * @see {@link #compareToLogically(Value)}
	 */
	public int compareTo(Value o, boolean logically) {
		if(logically) {
			if((this.getQuantity() instanceof Number || this.getQuantity() instanceof Key)
					&& (o.getQuantity() instanceof Number || o.getQuantity() instanceof Key)) {
				return Numbers.compare((Number) this.getQuantity(),
						(Number) o.getQuantity());
			}
			else {
				return this.toString().compareTo(o.toString());
			}
		}
		else {
			// push notForStorage values to the back so that we
			// are sure to reach forStorage values
			if(this.isNotForStorage()) {
				return this.equals(o) ? 0 : 1;
			}
			else if(o.isNotForStorage()) {
				return this.equals(o) ? 0 : -1;
			}
			else {
				return -1 * Longs.compare(this.timestamp, o.timestamp);
			}
		}
	}

	/**
	 * Logical comparison where appropriate casting is done to the encapsulated
	 * quantities.
	 * 
	 * @param o
	 * @return a negative integer, zero, or a positive integer as this object is
	 *         less than, equal to, or greater than the specified object.
	 */
	public int compareToLogically(Value o) {
		return compareTo(o, true);
	}

	/**
	 * Equality is only based on <code>quantity</code> and <code>type</code>, as
	 * to allow objects with different timestamps to be considered equal if
	 * necessary.
	 */
	@Override
	public boolean equals(Object obj) {
		if(obj instanceof Value) {
			final Value other = (Value) obj;
			return Objects.equal(this.getQuantity(), other.getQuantity())
					&& Objects.equal(type, type);
		}
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.cinchapi.concourse.db.Locatable#getLocator()
	 */
	/**
	 * Returns a 32 byte hash that represents the {@link #quantity} and
	 * {@link #type}. Locators are defined per quantity/type and not value, so
	 * two values will return the same locator if they express the same
	 * quantity/type. This uses the same semantics as the
	 * {@link #equals(Object)} method.
	 */
	@Override
	public byte[] getLocator() {
		return locator;
	}

	/**
	 * Return an object that represents the encapsulated <code>quantity</code>.
	 * 
	 * @return the value.
	 */
	public Object getQuantity() {
		return Utilities.getObjectFromByteBuffer(getQuantityBuffer(), type);
	}

	/**
	 * Return the associated <code>timestamp</code>. This is guaranteed to be
	 * unique amongst forStorage values so it a de facto identifier. For
	 * notForStorage values, the timestamp is always {@link #nil}.
	 * 
	 * @return the <code>timestamp</code>
	 */
	public long getTimestamp() {
		return timestamp;
	}

	/**
	 * Return a string description of the value type.
	 * 
	 * @return the value type
	 */
	public String getType() {
		return type.toString();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.cinchapi.concourse.db.ByteSized#getBytes()
	 */
	/**
	 * Return a byte array that represents the value with the following order:
	 * <ol>
	 * <li><strong>timestamp</strong> - first 8 bytes</li>
	 * <li><strong>type</strong> - next 4 bytes</li>
	 * <li><strong>locator</strong> - next 32 bytes</li>
	 * <li><strong>size</strong> - next 4 bytes</li>
	 * <li><strong>quantity</strong> - remaining bytes</li>
	 * </ol>
	 * 
	 * @return a byte array.
	 */
	@Override
	public byte[] getBytes() {
		return asByteBuffer().array();
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(getQuantity(), type);
	}

	/**
	 * Return <code>true</code> if the value is suitable for use in storage
	 * functions.
	 * 
	 * @return <code>true</code> of {@link Value#isNotForStorage()} is
	 *         <code>false</code>.
	 */
	public boolean isForStorage() {
		return !isNotForStorage();
	}

	/**
	 * Return <code>true</code> if the value is not suitable for storage
	 * functions and is only suitable for comparisons.
	 * 
	 * @return <code>true</code> if the timestamp is null.
	 */
	public boolean isNotForStorage() {
		return timestamp == nil;
	}

	@Override
	public int size() {
		return size;
	}

	@Override
	public String toString() {
		return getQuantity().toString();
	}

	@Override
	public void writeTo(FileChannel channel) throws IOException {
		Preconditions.checkState(isForStorage(),
				"Cannot write out a notForStorage value.");
		Writer.write(this, channel);
	}

	/**
	 * Return a new byte buffer that contains the value with the following
	 * order:
	 * <ol>
	 * <li><strong>timestamp</strong> - first 8 bytes</li>
	 * <li><strong>type</strong> - next 4 bytes</li>
	 * <li><strong>locator</strong> - next 32 bytes</li>
	 * <li><strong>size</strong> - next 4 bytes</li>
	 * <li><strong>quantity</strong> - remaining bytes</li>
	 * </ol>
	 * 
	 * @return a byte buffer.
	 */
	private ByteBuffer asByteBuffer() {
		ByteBuffer buffer = ByteBuffer.allocate(size());
		buffer.put(ByteBuffers.toByteBuffer(timestamp));
		buffer.put(ByteBuffers.toByteBuffer(type.ordinal()));
		buffer.put(ByteBuffer.wrap(locator));
		buffer.put(ByteBuffers.toByteBuffer(size()));
		buffer.put(getQuantityBuffer());
		buffer.rewind();
		return buffer;
	}

	/**
	 * Rewind and return {@link #quantity}. Use this method instead of accessing
	 * the variable directly.
	 * 
	 * @return
	 */
	private ByteBuffer getQuantityBuffer() {
		quantity.rewind();
		return quantity;
	}

	/**
	 * The value type contained within a {@link Value}.
	 */
	public enum Type {
		BOOLEAN, DOUBLE, FLOAT, INTEGER, LONG, RELATION, STRING;

		@Override
		public String toString() {
			return this.name().toLowerCase();
		}
	}

	/**
	 * Utilities for {@link Value}.
	 */
	private static class Utilities {

		/**
		 * Return a {@link ByteBuffer} that represents <code>object</code>.
		 * 
		 * @param object
		 * @return the byte buffer.
		 */
		public static ByteBuffer getByteBufferForObject(Object object) {
			Type type = getObjectType(object);
			return getByteBufferForObject(object, type);
		}

		/**
		 * Return a {@link ByteBuffer} that represents <code>object</code> of
		 * <code>type</code>.
		 * 
		 * @param object
		 * @return the byte buffer.
		 */
		public static ByteBuffer getByteBufferForObject(Object object, Type type) {
			ByteBuffer buffer = null;

			switch (type) {
			case BOOLEAN:
				buffer = ByteBuffers.toByteBuffer((boolean) object);
				break;
			case DOUBLE:
				buffer = ByteBuffers.toByteBuffer((double) object);
				break;
			case FLOAT:
				buffer = ByteBuffers.toByteBuffer((float) object);
				break;
			case INTEGER:
				buffer = ByteBuffers.toByteBuffer((int) object);
				break;
			case LONG:
				buffer = ByteBuffers.toByteBuffer((long) object);
				break;
			case RELATION:
				buffer = ByteBuffers.toByteBuffer(((Key) (object)).asLong());
				break;
			default:
				String _object = object.toString();
				Preconditions
						.checkArgument(
								_object.getBytes(ByteBuffers.charset()).length < maxQuantitySizeInBytes,
								"Cannot create a byte buffer for %s because it is larger than the %s maximum allowed bytes",
								object, maxQuantitySizeInBytes);
				buffer = ByteBuffers.toByteBuffer(object.toString());
				break;
			}

			return buffer;
		}

		/**
		 * Return the object of <code>type</code> that is represted by
		 * <code>buffer</code>
		 * 
		 * @param buffer
		 * @param type
		 * @return the object.
		 */
		public static Object getObjectFromByteBuffer(ByteBuffer buffer,
				Type type) {
			Object object = null;

			switch (type) {
			case BOOLEAN:
				object = ByteBuffers.getBoolean(buffer);
				break;
			case DOUBLE:
				object = ByteBuffers.getDouble(buffer);
				break;
			case FLOAT:
				object = ByteBuffers.getFloat(buffer);
				break;
			case INTEGER:
				object = ByteBuffers.getInt(buffer);
				break;
			case LONG:
				object = ByteBuffers.getLong(buffer);
				break;
			case RELATION:
				object = Key.create(ByteBuffers.getLong(buffer));
				break;
			default:
				object = ByteBuffers.getString(buffer);
				break;
			}

			return object;
		}

		/**
		 * Determine the {@link Type} for <code>value</code>.
		 * 
		 * @param value
		 * @return the value type.
		 */
		public static Type getObjectType(Object value) {
			Type type;
			if(value instanceof Boolean) {
				type = Type.BOOLEAN;
			}
			else if(value instanceof Double) {
				type = Type.DOUBLE;
			}
			else if(value instanceof Float) {
				type = Type.FLOAT;
			}
			else if(value instanceof Integer) {
				type = Type.INTEGER;
			}
			else if(value instanceof Long) {
				type = Type.LONG;
			}
			else if(value instanceof Key) {
				type = Type.RELATION;
			}
			else {
				type = Type.STRING;
			}
			return type;
		}
	}
}
