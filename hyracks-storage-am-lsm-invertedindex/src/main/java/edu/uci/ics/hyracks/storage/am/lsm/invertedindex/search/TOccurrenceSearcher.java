/*
 * Copyright 2009-2010 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.hyracks.storage.am.lsm.invertedindex.search;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import edu.uci.ics.hyracks.api.comm.IFrameTupleAccessor;
import edu.uci.ics.hyracks.api.context.IHyracksCommonContext;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.api.dataflow.value.RecordDescriptor;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.dataflow.common.comm.io.ArrayTupleBuilder;
import edu.uci.ics.hyracks.dataflow.common.comm.io.FrameTupleAccessor;
import edu.uci.ics.hyracks.dataflow.common.comm.io.FrameTupleAppender;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.FrameTupleReference;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.ITupleReference;
import edu.uci.ics.hyracks.dataflow.common.data.marshalling.IntegerSerializerDeserializer;
import edu.uci.ics.hyracks.dataflow.common.data.marshalling.UTF8StringSerializerDeserializer;
import edu.uci.ics.hyracks.storage.am.common.api.IIndexOperationContext;
import edu.uci.ics.hyracks.storage.am.common.api.IndexException;
import edu.uci.ics.hyracks.storage.am.common.ophelpers.MultiComparator;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.api.IInvertedIndex;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.api.IInvertedIndexSearchModifier;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.api.IInvertedIndexSearcher;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.api.IInvertedListCursor;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.exceptions.OccurrenceThresholdPanicException;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.ondisk.FixedSizeFrameTupleAccessor;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.ondisk.FixedSizeTupleReference;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.ondisk.OnDiskInvertedIndexSearchCursor;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.tokenizers.IBinaryTokenizer;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.tokenizers.IToken;

// TODO: The search procedure is rather confusing regarding cursor positions, hasNext() calls etc.
// Needs an overhaul some time.
public class TOccurrenceSearcher implements IInvertedIndexSearcher {

    protected final IHyracksCommonContext ctx;
    
    protected SearchResult newSearchResult;
    protected SearchResult prevSearchResult;    

    protected RecordDescriptor queryTokenRecDesc = new RecordDescriptor(
            new ISerializerDeserializer[] { UTF8StringSerializerDeserializer.INSTANCE });
    protected ArrayTupleBuilder queryTokenBuilder = new ArrayTupleBuilder(queryTokenRecDesc.getFieldCount());
    protected DataOutput queryTokenDos = queryTokenBuilder.getDataOutput();
    protected FrameTupleAppender queryTokenAppender;
    protected ByteBuffer queryTokenFrame;
    protected final FrameTupleReference searchKey = new FrameTupleReference();

    protected final IInvertedIndex invIndex;
    protected final MultiComparator invListCmp;
    protected int occurrenceThreshold;

    protected final int cursorCacheSize = 10;
    protected List<IInvertedListCursor> invListCursorCache = new ArrayList<IInvertedListCursor>(cursorCacheSize);
    protected List<IInvertedListCursor> invListCursors = new ArrayList<IInvertedListCursor>(cursorCacheSize);
    
    public TOccurrenceSearcher(IHyracksCommonContext ctx, IInvertedIndex invIndex) {
        this.ctx = ctx;
        this.invIndex = invIndex;
        this.invListCmp = MultiComparator.create(invIndex.getInvListCmpFactories());

        this.prevSearchResult = new SearchResult(invIndex.getInvListTypeTraits(), ctx);
        this.newSearchResult = new SearchResult(prevSearchResult);

        // Pre-create cursor objects.
        for (int i = 0; i < cursorCacheSize; i++) {
            invListCursorCache.add(invIndex.createInvertedListCursor());
        }

        queryTokenAppender = new FrameTupleAppender(ctx.getFrameSize());
        queryTokenFrame = ctx.allocateFrame();
    }

    public void reset() {
        prevSearchResult.clear();
        newSearchResult.clear();
    }

    public void search(OnDiskInvertedIndexSearchCursor resultCursor, InvertedIndexSearchPredicate searchPred,
            IIndexOperationContext ictx) throws HyracksDataException, IndexException {
        ITupleReference queryTuple = searchPred.getQueryTuple();
        int queryFieldIndex = searchPred.getQueryFieldIndex();
        IInvertedIndexSearchModifier searchModifier = searchPred.getSearchModifier();
        IBinaryTokenizer queryTokenizer = searchPred.getQueryTokenizer();

        queryTokenAppender.reset(queryTokenFrame, true);
        queryTokenizer.reset(queryTuple.getFieldData(queryFieldIndex), queryTuple.getFieldStart(queryFieldIndex),
                queryTuple.getFieldLength(queryFieldIndex));

        while (queryTokenizer.hasNext()) {
            queryTokenizer.next();
            queryTokenBuilder.reset();
            try {
                IToken token = queryTokenizer.getToken();
                token.serializeToken(queryTokenDos);
                queryTokenBuilder.addFieldEndOffset();
                // WARNING: assuming one frame is big enough to hold all tokens
                queryTokenAppender.append(queryTokenBuilder.getFieldEndOffsets(), queryTokenBuilder.getByteArray(), 0,
                        queryTokenBuilder.getSize());
            } catch (IOException e) {
                throw new HyracksDataException(e);
            }
        }

        FrameTupleAccessor queryTokenAccessor = new FrameTupleAccessor(ctx.getFrameSize(), queryTokenRecDesc);
        queryTokenAccessor.reset(queryTokenFrame);
        int numQueryTokens = queryTokenAccessor.getTupleCount();

        // Expand cursor cache if necessary.
        if (numQueryTokens > invListCursorCache.size()) {
            int diff = numQueryTokens - invListCursorCache.size();
            for (int i = 0; i < diff; i++) {
                invListCursorCache.add(invIndex.createInvertedListCursor());
            }
        }

        invListCursors.clear();
        for (int i = 0; i < numQueryTokens; i++) {
            searchKey.reset(queryTokenAccessor, i);
            invIndex.openInvertedListCursor(invListCursorCache.get(i), searchKey, ictx);
            invListCursors.add(invListCursorCache.get(i));
        }
        Collections.sort(invListCursors);
        
        occurrenceThreshold = searchModifier.getOccurrenceThreshold(invListCursors.size());
        // TODO: deal with panic cases properly
        if (occurrenceThreshold <= 0) {
            throw new OccurrenceThresholdPanicException("Merge Threshold is <= 0. Failing Search.");
        }
        
        int numPrefixLists = searchModifier.getNumPrefixLists(invListCursors.size());
        mergePrefixLists(numPrefixLists, numQueryTokens);
        mergeSuffixLists(numPrefixLists, numQueryTokens);

        resultCursor.open(null, searchPred);
    }

    protected void mergePrefixLists(int numPrefixTokens, int numQueryTokens) throws HyracksDataException,
            IndexException {
        for (int i = 0; i < numPrefixTokens; i++) {
            SearchResult swapTemp = prevSearchResult;
            prevSearchResult = newSearchResult;
            newSearchResult = swapTemp;
            newSearchResult.reset();

            invListCursors.get(i).pinPages();
            mergePrefixList(invListCursors.get(i), prevSearchResult, newSearchResult);
            invListCursors.get(i).unpinPages();
        }
    }

    protected void mergeSuffixLists(int numPrefixTokens, int numQueryTokens) throws HyracksDataException,
            IndexException {
        for (int i = numPrefixTokens; i < numQueryTokens; i++) {
            SearchResult swapTemp = prevSearchResult;
            prevSearchResult = newSearchResult;
            newSearchResult = swapTemp;
            newSearchResult.reset();

            invListCursors.get(i).pinPages();
            int numInvListElements = invListCursors.get(i).size();
            int currentNumResults = prevSearchResult.getNumResults();
            // Should we binary search the next list or should we sort-merge it?
            if (currentNumResults * Math.log(numInvListElements) < currentNumResults + numInvListElements) {
                mergeSuffixListProbe(invListCursors.get(i), prevSearchResult, newSearchResult, i, numQueryTokens);
            } else {
                mergeSuffixListScan(invListCursors.get(i), prevSearchResult, newSearchResult, i, numQueryTokens);
            }
            invListCursors.get(i).unpinPages();
        }
    }

    protected void mergeSuffixListProbe(IInvertedListCursor invListCursor, SearchResult prevSearchResult,
            SearchResult newSearchResult, int invListIx, int numQueryTokens) throws HyracksDataException, IndexException {

        int prevBufIdx = 0;
        int maxPrevBufIdx = prevSearchResult.getCurrentBufferIndex();
        ByteBuffer prevCurrentBuffer = prevSearchResult.getBuffers().get(0);

        FixedSizeFrameTupleAccessor resultFrameTupleAcc = prevSearchResult.getAccessor();
        FixedSizeTupleReference resultTuple = prevSearchResult.getTuple();

        int resultTidx = 0;

        resultFrameTupleAcc.reset(prevCurrentBuffer);

        while (resultTidx < resultFrameTupleAcc.getTupleCount()) {

            resultTuple.reset(prevCurrentBuffer.array(), resultFrameTupleAcc.getTupleStartOffset(resultTidx));
            int count = IntegerSerializerDeserializer.getInt(resultTuple.getFieldData(0),
                    resultTuple.getFieldStart(resultTuple.getFieldCount() - 1));

            if (invListCursor.containsKey(resultTuple, invListCmp)) {
                count++;
                newSearchResult.append(resultTuple, count);
            } else {
                if (count + numQueryTokens - invListIx > occurrenceThreshold) {
                    newSearchResult.append(resultTuple, count);
                }
            }

            resultTidx++;
            if (resultTidx >= resultFrameTupleAcc.getTupleCount()) {
                prevBufIdx++;
                if (prevBufIdx <= maxPrevBufIdx) {
                    prevCurrentBuffer = prevSearchResult.getBuffers().get(prevBufIdx);
                    resultFrameTupleAcc.reset(prevCurrentBuffer);
                    resultTidx = 0;
                }
            }
        }
    }

    protected void mergeSuffixListScan(IInvertedListCursor invListCursor, SearchResult prevSearchResult,
            SearchResult newSearchResult, int invListIx, int numQueryTokens)
            throws HyracksDataException, IndexException {
        
        int prevBufIdx = 0;
        int maxPrevBufIdx = prevSearchResult.getCurrentBufferIndex();
        ByteBuffer prevCurrentBuffer = prevSearchResult.getBuffers().get(0);

        FixedSizeFrameTupleAccessor resultFrameTupleAcc = prevSearchResult.getAccessor();
        FixedSizeTupleReference resultTuple = prevSearchResult.getTuple();
        
        boolean advanceCursor = true;
        boolean advancePrevResult = false;
        int resultTidx = 0;

        resultFrameTupleAcc.reset(prevCurrentBuffer);

        int invListTidx = 0;
        int invListNumTuples = invListCursor.size();

        if (invListCursor.hasNext())
            invListCursor.next();

        while (invListTidx < invListNumTuples && resultTidx < resultFrameTupleAcc.getTupleCount()) {

            ITupleReference invListTuple = invListCursor.getTuple();

            resultTuple.reset(prevCurrentBuffer.array(), resultFrameTupleAcc.getTupleStartOffset(resultTidx));

            int cmp = invListCmp.compare(invListTuple, resultTuple);
            if (cmp == 0) {
                int count = IntegerSerializerDeserializer.getInt(resultTuple.getFieldData(0),
                        resultTuple.getFieldStart(resultTuple.getFieldCount() - 1)) + 1;
                newSearchResult.append(resultTuple, count);
                advanceCursor = true;
                advancePrevResult = true;
            } else {
                if (cmp < 0) {
                    advanceCursor = true;
                    advancePrevResult = false;
                } else {
                    int count = IntegerSerializerDeserializer.getInt(resultTuple.getFieldData(0),
                            resultTuple.getFieldStart(resultTuple.getFieldCount() - 1));
                    if (count + numQueryTokens - invListIx > occurrenceThreshold) {
                        newSearchResult.append(resultTuple, count);
                    }
                    advanceCursor = false;
                    advancePrevResult = true;
                }
            }

            if (advancePrevResult) {
                resultTidx++;
                if (resultTidx >= resultFrameTupleAcc.getTupleCount()) {
                    prevBufIdx++;
                    if (prevBufIdx <= maxPrevBufIdx) {
                        prevCurrentBuffer = prevSearchResult.getBuffers().get(prevBufIdx);
                        resultFrameTupleAcc.reset(prevCurrentBuffer);
                        resultTidx = 0;
                    }
                }
            }

            if (advanceCursor) {
                invListTidx++;
                if (invListCursor.hasNext()) {
                    invListCursor.next();
                }
            }
        }

        // append remaining elements from previous result set
        while (resultTidx < resultFrameTupleAcc.getTupleCount()) {

            resultTuple.reset(prevCurrentBuffer.array(), resultFrameTupleAcc.getTupleStartOffset(resultTidx));

            int count = IntegerSerializerDeserializer.getInt(resultTuple.getFieldData(0),
                    resultTuple.getFieldStart(resultTuple.getFieldCount() - 1));
            if (count + numQueryTokens - invListIx > occurrenceThreshold) {
                newSearchResult.append(resultTuple, count);
            }

            resultTidx++;
            if (resultTidx >= resultFrameTupleAcc.getTupleCount()) {
                prevBufIdx++;
                if (prevBufIdx <= maxPrevBufIdx) {
                    prevCurrentBuffer = prevSearchResult.getBuffers().get(prevBufIdx);
                    resultFrameTupleAcc.reset(prevCurrentBuffer);
                    resultTidx = 0;
                }
            }
        }
    }

    protected void mergePrefixList(IInvertedListCursor invListCursor, SearchResult prevSearchResult,
            SearchResult newSearchResult) throws HyracksDataException, IndexException {
        
        int prevBufIdx = 0;
        int maxPrevBufIdx = prevSearchResult.getCurrentBufferIndex();
        ByteBuffer prevCurrentBuffer = prevSearchResult.getBuffers().get(0);

        FixedSizeFrameTupleAccessor resultFrameTupleAcc = prevSearchResult.getAccessor();
        FixedSizeTupleReference resultTuple = prevSearchResult.getTuple();
        
        boolean advanceCursor = true;
        boolean advancePrevResult = false;
        int resultTidx = 0;

        resultFrameTupleAcc.reset(prevCurrentBuffer);

        int invListTidx = 0;
        int invListNumTuples = invListCursor.size();

        if (invListCursor.hasNext())
            invListCursor.next();

        while (invListTidx < invListNumTuples && resultTidx < resultFrameTupleAcc.getTupleCount()) {

            ITupleReference invListTuple = invListCursor.getTuple();
            resultTuple.reset(prevCurrentBuffer.array(), resultFrameTupleAcc.getTupleStartOffset(resultTidx));

            int cmp = invListCmp.compare(invListTuple, resultTuple);
            if (cmp == 0) {
                int count = IntegerSerializerDeserializer.getInt(resultTuple.getFieldData(0),
                        resultTuple.getFieldStart(resultTuple.getFieldCount() - 1)) + 1;
                newSearchResult.append(resultTuple, count);
                advanceCursor = true;
                advancePrevResult = true;
            } else {
                if (cmp < 0) {
                    int count = 1;
                    newSearchResult.append(invListTuple, count);
                    advanceCursor = true;
                    advancePrevResult = false;
                } else {
                    int count = IntegerSerializerDeserializer.getInt(resultTuple.getFieldData(0),
                            resultTuple.getFieldStart(resultTuple.getFieldCount() - 1));
                    newSearchResult.append(resultTuple, count);
                    advanceCursor = false;
                    advancePrevResult = true;
                }
            }

            if (advancePrevResult) {
                resultTidx++;
                if (resultTidx >= resultFrameTupleAcc.getTupleCount()) {
                    prevBufIdx++;
                    if (prevBufIdx <= maxPrevBufIdx) {
                        prevCurrentBuffer = prevSearchResult.getBuffers().get(prevBufIdx);
                        resultFrameTupleAcc.reset(prevCurrentBuffer);
                        resultTidx = 0;
                    }
                }
            }

            if (advanceCursor) {
                invListTidx++;
                if (invListCursor.hasNext()) {
                    invListCursor.next();
                }
            }
        }

        // append remaining new elements from inverted list
        while (invListTidx < invListNumTuples) {
            ITupleReference invListTuple = invListCursor.getTuple();
            newSearchResult.append(invListTuple, 1);
            invListTidx++;
            if (invListCursor.hasNext()) {
                invListCursor.next();
            }
        }

        // append remaining elements from previous result set
        while (resultTidx < resultFrameTupleAcc.getTupleCount()) {

            resultTuple.reset(prevCurrentBuffer.array(), resultFrameTupleAcc.getTupleStartOffset(resultTidx));

            int count = IntegerSerializerDeserializer.getInt(resultTuple.getFieldData(0),
                    resultTuple.getFieldStart(resultTuple.getFieldCount() - 1));
            newSearchResult.append(resultTuple, count);

            resultTidx++;
            if (resultTidx >= resultFrameTupleAcc.getTupleCount()) {
                prevBufIdx++;
                if (prevBufIdx <= maxPrevBufIdx) {
                    prevCurrentBuffer = prevSearchResult.getBuffers().get(prevBufIdx);
                    resultFrameTupleAcc.reset(prevCurrentBuffer);
                    resultTidx = 0;
                }
            }
        }
    }

    public IFrameTupleAccessor createResultFrameTupleAccessor() {
        return new FixedSizeFrameTupleAccessor(ctx.getFrameSize(), newSearchResult.getTypeTraits());
    }

    public ITupleReference createResultFrameTupleReference() {
        return new FixedSizeTupleReference(newSearchResult.getTypeTraits());
    }

    @Override
    public List<ByteBuffer> getResultBuffers() {
        return newSearchResult.getBuffers();
    }

    @Override
    public int getNumValidResultBuffers() {
        return newSearchResult.getCurrentBufferIndex() + 1;
    }

    public int getOccurrenceThreshold() {
        return occurrenceThreshold;
    }

    public void printNewResults(int maxResultBufIdx, List<ByteBuffer> buffer) {
        StringBuffer strBuffer = new StringBuffer();
        FixedSizeFrameTupleAccessor resultFrameTupleAcc = prevSearchResult.getAccessor();
        for (int i = 0; i <= maxResultBufIdx; i++) {
            ByteBuffer testBuf = buffer.get(i);
            resultFrameTupleAcc.reset(testBuf);
            for (int j = 0; j < resultFrameTupleAcc.getTupleCount(); j++) {
                strBuffer.append(IntegerSerializerDeserializer.getInt(resultFrameTupleAcc.getBuffer().array(),
                        resultFrameTupleAcc.getFieldStartOffset(j, 0)) + ",");
                strBuffer.append(IntegerSerializerDeserializer.getInt(resultFrameTupleAcc.getBuffer().array(),
                        resultFrameTupleAcc.getFieldStartOffset(j, 1)) + " ");
            }
        }
        System.out.println(strBuffer.toString());
    }
}