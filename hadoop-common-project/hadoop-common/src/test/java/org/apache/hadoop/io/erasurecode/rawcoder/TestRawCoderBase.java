/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.io.erasurecode.rawcoder;

import org.apache.hadoop.io.erasurecode.ECChunk;
import org.apache.hadoop.io.erasurecode.TestCoderBase;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Constructor;

/**
 * Raw coder test base with utilities.
 */
public abstract class TestRawCoderBase extends TestCoderBase {
  protected Class<? extends RawErasureEncoder> encoderClass;
  protected Class<? extends RawErasureDecoder> decoderClass;
  protected RawErasureEncoder encoder;
  protected RawErasureDecoder decoder;

  /**
   * Doing twice to test if the coders can be repeatedly reused. This matters
   * as the underlying coding buffers are shared, which may have bugs.
   */
  protected void testCodingDoMixAndTwice() {
    testCodingDoMixed();
    testCodingDoMixed();
  }

  /**
   * Doing in mixed buffer usage model to test if the coders can be repeatedly
   * reused with different buffer usage model. This matters as the underlying
   * coding buffers are shared, which may have bugs.
   */
  protected void testCodingDoMixed() {
    testCoding(true);
    testCoding(false);
  }

  /**
   * Generating source data, encoding, recovering and then verifying.
   * RawErasureCoder mainly uses ECChunk to pass input and output data buffers,
   * it supports two kinds of ByteBuffers, one is array backed, the other is
   * direct ByteBuffer. Use usingDirectBuffer indicate which case to test.
   *
   * @param usingDirectBuffer
   */
  protected void testCoding(boolean usingDirectBuffer) {
    this.usingDirectBuffer = usingDirectBuffer;
    prepareCoders();

    /**
     * The following runs will use 3 different chunkSize for inputs and outputs,
     * to verify the same encoder/decoder can process variable width of data.
     */
    performTestCoding(baseChunkSize, true, false, false, false);
    performTestCoding(baseChunkSize - 17, false, false, false, true);
    performTestCoding(baseChunkSize + 16, true, false, false, false);
  }

  /**
   * Similar to above, but perform negative cases using bad input for encoding.
   * @param usingDirectBuffer
   */
  protected void testCodingWithBadInput(boolean usingDirectBuffer) {
    this.usingDirectBuffer = usingDirectBuffer;
    prepareCoders();

    try {
      performTestCoding(baseChunkSize, false, true, false, true);
      Assert.fail("Encoding test with bad input should fail");
    } catch (Exception e) {
      // Expected
    }
  }

  /**
   * Similar to above, but perform negative cases using bad output for decoding.
   * @param usingDirectBuffer
   */
  protected void testCodingWithBadOutput(boolean usingDirectBuffer) {
    this.usingDirectBuffer = usingDirectBuffer;
    prepareCoders();

    try {
      performTestCoding(baseChunkSize, false, false, true, true);
      Assert.fail("Decoding test with bad output should fail");
    } catch (Exception e) {
      // Expected
    }
  }

  @Test
  public void testCodingWithErasingTooMany() {
    try {
      testCoding(true);
      Assert.fail("Decoding test erasing too many should fail");
    } catch (Exception e) {
      // Expected
    }

    try {
      testCoding(false);
      Assert.fail("Decoding test erasing too many should fail");
    } catch (Exception e) {
      // Expected
    }
  }

  private void performTestCoding(int chunkSize, boolean usingSlicedBuffer,
                                 boolean useBadInput, boolean useBadOutput,
                                 boolean allowChangeInputs) {
    setChunkSize(chunkSize);
    prepareBufferAllocator(usingSlicedBuffer);
    setAllowChangeInputs(allowChangeInputs);

    dumpSetting();

    // Generate data and encode
    ECChunk[] dataChunks = prepareDataChunksForEncoding();
    if (useBadInput) {
      corruptSomeChunk(dataChunks);
    }
    dumpChunks("Testing data chunks", dataChunks);

    ECChunk[] parityChunks = prepareParityChunksForEncoding();

    // Backup all the source chunks for later recovering because some coders
    // may affect the source data.
    ECChunk[] clonedDataChunks = cloneChunksWithData(dataChunks);
    markChunks(dataChunks);

    encoder.encode(dataChunks, parityChunks);
    dumpChunks("Encoded parity chunks", parityChunks);

    if (!allowChangeInputs) {
      restoreChunksFromMark(dataChunks);
      compareAndVerify(clonedDataChunks, dataChunks);
    }

    // Backup and erase some chunks
    ECChunk[] backupChunks = backupAndEraseChunks(clonedDataChunks, parityChunks);

    // Decode
    ECChunk[] inputChunks = prepareInputChunksForDecoding(
        clonedDataChunks, parityChunks);

    // Remove unnecessary chunks, allowing only least required chunks to be read.
    ensureOnlyLeastRequiredChunks(inputChunks);

    ECChunk[] recoveredChunks = prepareOutputChunksForDecoding();
    if (useBadOutput) {
      corruptSomeChunk(recoveredChunks);
    }

    ECChunk[] clonedInputChunks = null;
    if (!allowChangeInputs) {
      markChunks(inputChunks);
      clonedInputChunks = cloneChunksWithData(inputChunks);
    }

    dumpChunks("Decoding input chunks", inputChunks);
    decoder.decode(inputChunks, getErasedIndexesForDecoding(), recoveredChunks);
    dumpChunks("Decoded/recovered chunks", recoveredChunks);

    if (!allowChangeInputs) {
      restoreChunksFromMark(inputChunks);
      compareAndVerify(clonedInputChunks, inputChunks);
    }

    // Compare
    compareAndVerify(backupChunks, recoveredChunks);
  }

  protected void setAllowChangeInputs(boolean allowChangeInputs) {
    this.allowChangeInputs = allowChangeInputs;
    encoder.setCoderOption(CoderOption.ALLOW_CHANGE_INPUTS, allowChangeInputs);
    decoder.setCoderOption(CoderOption.ALLOW_CHANGE_INPUTS, allowChangeInputs);
  }

  protected void prepareCoders() {
    if (encoder == null) {
      encoder = createEncoder();
    }

    if (decoder == null) {
      decoder = createDecoder();
    }
  }

  protected void ensureOnlyLeastRequiredChunks(ECChunk[] inputChunks) {
    int leastRequiredNum = numDataUnits;
    int erasedNum = erasedDataIndexes.length + erasedParityIndexes.length;
    int goodNum = inputChunks.length - erasedNum;
    int redundantNum = goodNum - leastRequiredNum;

    for (int i = 0; i < inputChunks.length && redundantNum > 0; i++) {
      if (inputChunks[i] != null) {
        inputChunks[i] = null; // Setting it null, not needing it actually
        redundantNum--;
      }
    }
  }

  /**
   * Create the raw erasure encoder to test
   * @return
   */
  protected RawErasureEncoder createEncoder() {
    RawErasureEncoder encoder;
    try {
      Constructor<? extends RawErasureEncoder> constructor =
              (Constructor<? extends RawErasureEncoder>)
                      encoderClass.getConstructor(int.class, int.class);
      encoder = constructor.newInstance(numDataUnits, numParityUnits);
    } catch (Exception e) {
      throw new RuntimeException("Failed to create encoder", e);
    }

    encoder.setConf(getConf());
    return encoder;
  }

  /**
   * create the raw erasure decoder to test
   * @return
   */
  protected RawErasureDecoder createDecoder() {
    RawErasureDecoder decoder;
    try {
      Constructor<? extends RawErasureDecoder> constructor =
              (Constructor<? extends RawErasureDecoder>)
                      decoderClass.getConstructor(int.class, int.class);
      decoder = constructor.newInstance(numDataUnits, numParityUnits);
    } catch (Exception e) {
      throw new RuntimeException("Failed to create decoder", e);
    }

    decoder.setConf(getConf());
    return decoder;
  }

  /**
   * Tests that the input buffer's position is moved to the end after
   * encode/decode.
   */
  protected void testInputPosition(boolean usingDirectBuffer) {
    this.usingDirectBuffer = usingDirectBuffer;
    prepareCoders();
    prepareBufferAllocator(false);

    // verify encode
    ECChunk[] dataChunks = prepareDataChunksForEncoding();
    ECChunk[] parityChunks = prepareParityChunksForEncoding();
    ECChunk[] clonedDataChunks = cloneChunksWithData(dataChunks);
    encoder.encode(dataChunks, parityChunks);
    verifyBufferPositionAtEnd(dataChunks);

    // verify decode
    backupAndEraseChunks(clonedDataChunks, parityChunks);
    ECChunk[] inputChunks = prepareInputChunksForDecoding(
        clonedDataChunks, parityChunks);
    ensureOnlyLeastRequiredChunks(inputChunks);
    ECChunk[] recoveredChunks = prepareOutputChunksForDecoding();
    decoder.decode(inputChunks, getErasedIndexesForDecoding(), recoveredChunks);
    verifyBufferPositionAtEnd(inputChunks);
  }

  private void verifyBufferPositionAtEnd(ECChunk[] inputChunks) {
    for (ECChunk chunk : inputChunks) {
      if (chunk != null) {
        Assert.assertEquals(0, chunk.getBuffer().remaining());
      }
    }
  }
}