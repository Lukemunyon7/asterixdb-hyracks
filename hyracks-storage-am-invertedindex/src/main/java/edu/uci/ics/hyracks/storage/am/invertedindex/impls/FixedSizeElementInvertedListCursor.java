package edu.uci.ics.hyracks.storage.am.invertedindex.impls;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;

import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.api.dataflow.value.ITypeTrait;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.ITupleReference;
import edu.uci.ics.hyracks.storage.am.common.ophelpers.MultiComparator;
import edu.uci.ics.hyracks.storage.am.invertedindex.api.IInvertedListCursor;
import edu.uci.ics.hyracks.storage.common.buffercache.IBufferCache;
import edu.uci.ics.hyracks.storage.common.buffercache.ICachedPage;
import edu.uci.ics.hyracks.storage.common.file.BufferedFileHandle;

public class FixedSizeElementInvertedListCursor implements IInvertedListCursor {

    private final IBufferCache bufferCache;
    private final int fileId;
    private final int elementSize;
    private int currentElementIx;
    private int currentOff;
    private int currentPageIx;    
    
    private int startPageId;
    private int endPageId;
    private int startOff;
    private int numElements;
    
    private final FixedSizeTupleReference tuple;
    private ICachedPage[] pages = new ICachedPage[10];
    private int[] elementIndexes = new int[10];
    
    public FixedSizeElementInvertedListCursor(IBufferCache bufferCache, int fileId, ITypeTrait[] invListFields) {
        this.bufferCache = bufferCache;
        this.fileId = fileId;
        this.currentElementIx = 0;
        this.currentPageIx = 0;        
        
        int tmp = 0;
        for(int i = 0; i < invListFields.length; i++) {
            tmp += invListFields[i].getStaticallyKnownDataLength();
        }
        elementSize = tmp;
        this.currentOff = -elementSize;
        this.tuple = new FixedSizeTupleReference(invListFields);
    }
    
    @Override
    public boolean hasNext() {
        if(currentElementIx < numElements) return true;
        else return false;
    }

    @Override
    public void next() {
        //System.out.println("NEXT: " + currentOff + " " + elementSize + " " + bufferCache.getPageSize());
        if(currentOff + elementSize >= bufferCache.getPageSize()) {
            currentPageIx++;
            currentOff = 0;            
        }
        else {
            currentOff += elementSize;
        }
        
        currentElementIx++;
        tuple.reset(pages[currentPageIx].getBuffer().array(), currentOff);
    }

    @Override
    public void pinPagesAsync() {
        
        
    }

    @Override
    public void pinPagesSync() throws HyracksDataException {                        
        int pix = 0;
        for(int i = startPageId; i <= endPageId; i++) {            
            pages[pix] = bufferCache.pin(BufferedFileHandle.getDiskPageId(fileId, i), false);
            pages[pix].acquireReadLatch();
            pix++;
        }        
    }

    @Override
    public void unpinPages() throws HyracksDataException {                
        int numPages = endPageId - startPageId + 1;
        for(int i = 0; i < numPages; i++) {
            pages[i].releaseReadLatch();
            bufferCache.unpin(pages[i]);
        }        
    }
          
    @Override
    public void positionCursor(int elementIx) {
        int numPages = endPageId - startPageId + 1;
        
        currentPageIx = binarySearch(elementIndexes, 0, numPages, elementIx);                
        if(currentPageIx < 0) {
            throw new IndexOutOfBoundsException("Requested index: " + elementIx + " from array with numElements: " + numElements);
        }
        
        if(currentPageIx == 0) {
            currentOff = startOff + elementIx * elementSize;
        }
        else {
            int relativeElementIx = elementIx - elementIndexes[currentPageIx-1] - 1;
            currentOff = relativeElementIx * elementSize;
        }
        
        currentElementIx = elementIx;
        tuple.reset(pages[currentPageIx].getBuffer().array(), currentOff);
    }
    
    @Override
    public boolean containsKey(ITupleReference searchTuple, MultiComparator invListCmp) {
        int mid;
        int begin = 0;
        int end = numElements - 1;

        while (begin <= end) {
            mid = (begin + end) / 2;
            positionCursor(mid);
            int cmp = invListCmp.compare(searchTuple, tuple);
            if (cmp < 0) {
                end = mid - 1;
            } else if (cmp > 0) {
                begin = mid + 1;
            } else {                
                return true;
            }
        }
        
        return false;
    }     
        
    @Override
    public void reset(int startPageId, int endPageId, int startOff, int numElements) {
        this.startPageId = startPageId;
        this.endPageId = endPageId;
        this.startOff = startOff;
        this.numElements = numElements;
        this.currentElementIx = 0;
        this.currentPageIx = 0;
        this.currentOff = startOff - elementSize;
        
        int numPages = endPageId - startPageId + 1;
        if(numPages > pages.length) {
            pages = new ICachedPage[endPageId - startPageId + 1];
            elementIndexes = new int[endPageId - startPageId + 1];
        }               
        
        // fill elementIndexes
        // first page
        int cumulElements = (bufferCache.getPageSize() - startOff) / elementSize;
        elementIndexes[0] = cumulElements-1;          
        
        // middle, full pages        
        for(int i = 1; i < numPages-1; i++) {
            elementIndexes[i] = elementIndexes[i-1] + (bufferCache.getPageSize() / elementSize);
        }
        
        // last page
        elementIndexes[numPages-1] = numElements-1;
    }    
       
    @Override
    public String printInvList(ISerializerDeserializer[] serdes) throws HyracksDataException {        
        int oldCurrentOff = currentOff;
        int oldCurrentPageId = currentPageIx;
        int oldCurrentElementIx = currentElementIx;
        
        currentOff = startOff - elementSize;
        currentPageIx = 0;
        currentElementIx = 0;
        
        StringBuilder strBuilder = new StringBuilder();
        
        int count = 0;
        while(hasNext()) {                        
            next();
            count++;                                                
            
            // System.out.println(offset + " " + currentElementIx);           
            
            for(int i = 0; i < tuple.getFieldCount(); i++) {
                ByteArrayInputStream inStream = new ByteArrayInputStream(tuple.getFieldData(i), tuple.getFieldStart(i), tuple.getFieldLength(i));
                DataInput dataIn = new DataInputStream(inStream);            
                Object o = serdes[i].deserialize(dataIn);
                strBuilder.append(o.toString());
                if(i+1 < tuple.getFieldCount()) strBuilder.append(",");
            }
            strBuilder.append(" ");
        }
        //System.out.println("PRINT COUNT: " + count);
                
        // reset previous state
        currentOff = oldCurrentOff;
        currentPageIx = oldCurrentPageId;
        currentElementIx = oldCurrentElementIx;
        
        return strBuilder.toString();
    }    
    
    public String printCurrentElement(ISerializerDeserializer[] serdes) throws HyracksDataException { 
        StringBuilder strBuilder = new StringBuilder();
        for(int i = 0; i < tuple.getFieldCount(); i++) {
            ByteArrayInputStream inStream = new ByteArrayInputStream(tuple.getFieldData(i), tuple.getFieldStart(i), tuple.getFieldLength(i));
            DataInput dataIn = new DataInputStream(inStream);            
            Object o = serdes[i].deserialize(dataIn);
            strBuilder.append(o.toString());
            if(i+1 < tuple.getFieldCount()) strBuilder.append(",");
        }      
        return strBuilder.toString();
    }
    
    private int binarySearch(int[] arr, int arrStart, int arrLength, int key) {
        int mid;
        int begin = arrStart;
        int end = arrStart + arrLength - 1;

        while (begin <= end) {
            mid = (begin + end) / 2;                                
            int cmp = (key - arr[mid]);
            if (cmp < 0) {
                end = mid - 1;
            } else if (cmp > 0) {
                begin = mid + 1;
            } else {                
                return mid;
            }
        }
        
        if(begin > arr.length - 1) return -1;
        if(key < arr[begin]) return begin;
        else return -1;
    }

    @Override
    public int compareTo(IInvertedListCursor invListCursor) {
        return numElements - invListCursor.getNumElements();
    }

    @Override
    public int getEndPageId() {
        return endPageId;
    }

    @Override
    public int getNumElements() {
        return numElements;
    }

    @Override
    public int getStartOff() {
        return startOff;
    }

    @Override
    public int getStartPageId() {
        return startPageId;
    }

    public int getOffset() {
        return currentOff;
    }

    public ICachedPage getPage() {
        return pages[currentPageIx];
    }
    
    @Override
    public ITupleReference getTuple() {        
        return tuple;
    }
}