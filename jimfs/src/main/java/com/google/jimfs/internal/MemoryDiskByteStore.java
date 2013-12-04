/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jimfs.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.primitives.UnsignedBytes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Byte store backed by a {@link MemoryDisk}.
 *
 * @author Colin Decker
 */
final class MemoryDiskByteStore extends ByteStore {

  private final MemoryDisk disk;
  private final IntList blocks;
  private long size;

  public MemoryDiskByteStore(MemoryDisk disk) {
    this(disk, new IntList(32), 0);
  }

  private MemoryDiskByteStore(MemoryDisk disk, IntList blocks, long size) {
    this.disk = checkNotNull(disk);
    this.blocks = checkNotNull(blocks);

    checkArgument(size >= 0);
    this.size = size;
  }

  @Override
  public long currentSize() {
    return size;
  }

  @Override
  protected ByteStore createCopy() {
    IntList copyBlocks = new IntList(Math.max(blocks.size() * 2, 32));
    disk.allocate(copyBlocks, blocks.size());

    for (int i = 0; i < blocks.size(); i++) {
      int block = blocks.get(i);
      int copy = copyBlocks.get(i);
      disk.copy(block, copy);
    }
    return new MemoryDiskByteStore(disk, copyBlocks, size);
  }

  @Override
  protected final void deleteContents() {
    disk.free(blocks);
    size = 0;
  }

  @Override
  public boolean truncate(long size) {
    if (size >= this.size) {
      return false;
    }

    long lastPosition = size - 1;
    this.size = size;

    int newBlockCount = blockIndex(lastPosition) + 1;
    int blocksToRemove = blocks.size() - newBlockCount;
    if (blocksToRemove > 0) {
      disk.free(blocks, blocksToRemove);
    }

    return true;
  }

  /**
   * Prepares for a write of len bytes starting at position pos.
   */
  private void prepareForWrite(long pos, long len) {
    long end = pos + len;

    // allocate any additional blocks needed
    int lastBlockIndex = blocks.size() - 1;
    int endBlockIndex = blockIndex(end - 1);

    if (endBlockIndex > lastBlockIndex) {
      int additionalBlocksNeeded = endBlockIndex - lastBlockIndex;
      disk.allocate(blocks, additionalBlocksNeeded);
    }

    // zero bytes between current size and pos
    if (pos > size) {
      long remaining = pos - size;

      int blockIndex = blockIndex(size);
      int block = blocks.get(blockIndex);
      int off = offsetInBlock(size);

      remaining -= disk.zero(block, off, length(off, remaining));

      while (remaining > 0) {
        block = blocks.get(++blockIndex);

        remaining -= disk.zero(block, 0, length(remaining));
      }

      size = pos;
    }
  }

  @Override
  public int write(long pos, byte b) {
    prepareForWrite(pos, 1);

    int block = blocks.get(blockIndex(pos));
    int off = offsetInBlock(pos);
    disk.put(block, off, b);

    if (pos >= size) {
      size = pos + 1;
    }

    return 1;
  }

  @Override
  public int write(long pos, byte[] b, int off, int len) {
    prepareForWrite(pos, len);

    if (len == 0) {
      return 0;
    }

    int remaining = len;

    int blockIndex = blockIndex(pos);
    int block = blockForWrite(blockIndex);
    int offInBlock = offsetInBlock(pos);

    int written = disk.put(block, offInBlock, b, off, length(offInBlock, remaining));
    remaining -= written;
    off += written;

    while (remaining > 0) {
      block = blocks.get(++blockIndex);

      written = disk.put(block, 0, b, off, length(remaining));
      remaining -= written;
      off += written;
    }

    long newPos = pos + len;
    if (newPos > size) {
      size = newPos;
    }

    return len;
  }

  @Override
  public int write(long pos, ByteBuffer buf) {
    int len = buf.remaining();

    prepareForWrite(pos, len);

    if (len == 0) {
      return 0;
    }

    int bytesToWrite = buf.remaining();

    int blockIndex = blockIndex(pos);
    int block = blockForWrite(blockIndex);
    int off = offsetInBlock(pos);

    disk.put(block, off, buf);

    while (buf.hasRemaining()) {
      block = blocks.get(++blockIndex);

      disk.put(block, 0, buf);
    }

    if (pos + bytesToWrite > size) {
      size = pos + bytesToWrite;
    }

    return bytesToWrite;
  }

  @Override
  public long transferFrom(ReadableByteChannel src, long pos, long count) throws IOException {
    prepareForWrite(pos, 0);

    if (count == 0) {
      return 0;
    }

    long remaining = count;

    int blockIndex = blockIndex(pos);
    int block = blockForWrite(blockIndex);
    int off = offsetInBlock(pos);

    ByteBuffer buf = disk.asByteBuffer(block, off, length(off, remaining));

    int read = 0;
    while (buf.hasRemaining()) {
      read = src.read(buf);
      if (read == -1) {
        break;
      }

      remaining -= read;
    }

    if (read != -1) {
      outer: while (remaining > 0) {
        block = blockForWrite(++blockIndex);

        buf = disk.asByteBuffer(block, 0, length(remaining));
        while (buf.hasRemaining()) {
          read = src.read(buf);
          if (read == -1) {
            break outer;
          }

          remaining -= read;
        }
      }
    }

    long written = count - remaining;
    long newPos = pos + written;
    if (newPos > size) {
      size = newPos;
    }

    return written;
  }

  @Override
  public int read(long pos) {
    if (pos >= size) {
      return -1;
    }

    int block = blocks.get(blockIndex(pos));
    int off = offsetInBlock(pos);
    return UnsignedBytes.toInt(disk.get(block, off));
  }

  @Override
  public int read(long pos, byte[] b, int off, int len) {
    // since max is len (an int), result is guaranteed to be an int
    int bytesToRead = (int) bytesToRead(pos, len);

    if (bytesToRead > 0) {
      int remaining = bytesToRead;

      int blockIndex = blockIndex(pos);
      int block = blocks.get(blockIndex);
      int offsetInBlock = offsetInBlock(pos);

      int read = disk.get(block, offsetInBlock, b, off, length(offsetInBlock, remaining));
      remaining -= read;
      off += read;

      while (remaining > 0) {
        int index = ++blockIndex;
        block = blocks.get(index);

        read = disk.get(block, 0, b, off, length(remaining));
        remaining -= read;
        off += read;
      }
    }

    return bytesToRead;
  }

  @Override
  public int read(long pos, ByteBuffer buf) {
    // since max is buf.remaining() (an int), result is guaranteed to be an int
    int bytesToRead = (int) bytesToRead(pos, buf.remaining());

    if (bytesToRead > 0) {
      int remaining = bytesToRead;

      int blockIndex = blockIndex(pos);
      int block = blocks.get(blockIndex);
      int off = offsetInBlock(pos);

      remaining -= disk.get(block, off, buf, length(off, remaining));

      while (remaining > 0) {
        int index = ++blockIndex;
        block = blocks.get(index);
        remaining -= disk.get(block, 0, buf, length(remaining));
      }
    }

    return bytesToRead;
  }

  @Override
  public long transferTo(long pos, long count, WritableByteChannel dest) throws IOException {
    long bytesToRead = bytesToRead(pos, count);

    if (bytesToRead > 0) {
      long remaining = bytesToRead;

      int blockIndex = blockIndex(pos);
      int block = blocks.get(blockIndex);
      int off = offsetInBlock(pos);

      ByteBuffer buf = disk.asByteBuffer(block, off, length(off, remaining));
      while (buf.hasRemaining()) {
        remaining -= dest.write(buf);
      }
      buf.clear();

      while (remaining > 0) {
        int index = ++blockIndex;
        block = blocks.get(index);

        buf = disk.asByteBuffer(block, 0, length(remaining));
        while (buf.hasRemaining()) {
          remaining -= dest.write(buf);
        }
        buf.clear();
      }
    }

    return Math.max(bytesToRead, 0); // don't return -1 for this method
  }

  /**
   * Gets the block at the given index, expanding to create the block if necessary.
   */
  private int blockForWrite(int index) {
    int blockCount = blocks.size();
    if (index >= blockCount) {
      int additionalBlocksNeeded = index - blockCount + 1;
      disk.allocate(blocks, additionalBlocksNeeded);
    }

    return blocks.get(index);
  }

  private int blockIndex(long position) {
    return (int) (position / disk.blockSize());
  }

  private int offsetInBlock(long position) {
    return (int) (position % disk.blockSize());
  }

  private int length(long max) {
    return (int) Math.min(disk.blockSize(), max);
  }

  private int length(int off, long max) {
    return (int) Math.min(disk.blockSize() - off, max);
  }

  /**
   * Returns the number of bytes that can be read starting at position {@code pos} (up to a maximum
   * of {@code max}) or -1 if {@code pos} is greater than or equal to the current size.
   */
  private long bytesToRead(long pos, long max) {
    long available = size - pos;
    if (available <= 0) {
      return -1;
    }
    return Math.min(available, max);
  }
}