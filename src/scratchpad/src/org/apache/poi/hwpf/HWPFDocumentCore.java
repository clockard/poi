/* ====================================================================
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
==================================================================== */

package org.apache.poi.hwpf;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.security.GeneralSecurityException;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.POIDocument;
import org.apache.poi.hpsf.PropertySet;
import org.apache.poi.hssf.record.crypto.Biff8EncryptionKey;
import org.apache.poi.hwpf.model.CHPBinTable;
import org.apache.poi.hwpf.model.FibBase;
import org.apache.poi.hwpf.model.FileInformationBlock;
import org.apache.poi.hwpf.model.FontTable;
import org.apache.poi.hwpf.model.ListTables;
import org.apache.poi.hwpf.model.PAPBinTable;
import org.apache.poi.hwpf.model.SectionTable;
import org.apache.poi.hwpf.model.StyleSheet;
import org.apache.poi.hwpf.model.TextPieceTable;
import org.apache.poi.hwpf.usermodel.ObjectPoolImpl;
import org.apache.poi.hwpf.usermodel.ObjectsPool;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.poifs.crypt.ChunkedCipherInputStream;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.BoundedInputStream;
import org.apache.poi.util.IOUtils;
import org.apache.poi.util.Internal;
import org.apache.poi.util.LittleEndianByteArrayInputStream;


/**
 * This class holds much of the core of a Word document, but
 *  without some of the table structure information.
 * You generally want to work with one of
 *  {@link HWPFDocument} or {@link HWPFOldDocument}
 */
public abstract class HWPFDocumentCore extends POIDocument {
    protected static final String STREAM_OBJECT_POOL = "ObjectPool";
    protected static final String STREAM_WORD_DOCUMENT = "WordDocument";
    protected static final String STREAM_TABLE_0 = "0Table";
    protected static final String STREAM_TABLE_1 = "1Table";

    private static final int FIB_BASE_LEN = 68;

    /** Holds OLE2 objects */
    protected ObjectPoolImpl _objectPool;

    /** The FIB */
    protected FileInformationBlock _fib;

    /** Holds styles for this document.*/
    protected StyleSheet _ss;

    /** Contains formatting properties for text*/
    protected CHPBinTable _cbt;

    /** Contains formatting properties for paragraphs*/
    protected PAPBinTable _pbt;

    /** Contains formatting properties for sections.*/
    protected SectionTable _st;

    /** Holds fonts for this document.*/
    protected FontTable _ft;

    /** Hold list tables */
    protected ListTables _lt;

    /** main document stream buffer*/
    protected byte[] _mainStream;

    private EncryptionInfo _encryptionInfo;

    protected HWPFDocumentCore() {
        super((DirectoryNode)null);
    }

    /**
     * Takes an InputStream, verifies that it's not RTF or PDF, builds a
     *  POIFSFileSystem from it, and returns that.
     */
    public static POIFSFileSystem verifyAndBuildPOIFS(InputStream istream) throws IOException {
    	// Open a PushbackInputStream, so we can peek at the first few bytes
    	PushbackInputStream pis = new PushbackInputStream(istream,6);
    	byte[] first6 = IOUtils.toByteArray(pis, 6);

    	// Does it start with {\rtf ? If so, it's really RTF
    	if(first6[0] == '{' && first6[1] == '\\' && first6[2] == 'r'
    		&& first6[3] == 't' && first6[4] == 'f') {
    		throw new IllegalArgumentException("The document is really a RTF file");
    	} else if(first6[0] == '%' && first6[1] == 'P' && first6[2] == 'D' && first6[3] == 'F' ) {
    		throw new IllegalArgumentException("The document is really a PDF file");
    	}

    	// OK, so it's neither RTF nor PDF
    	// Open a POIFSFileSystem on the (pushed back) stream
    	pis.unread(first6);
    	return new POIFSFileSystem(pis);
    }

    /**
     * This constructor loads a Word document from an InputStream.
     *
     * @param istream The InputStream that contains the Word document.
     * @throws IOException If there is an unexpected IOException from the passed
     *         in InputStream.
     */
    public HWPFDocumentCore(InputStream istream) throws IOException {
        //do Ole stuff
        this( verifyAndBuildPOIFS(istream) );
    }

    /**
     * This constructor loads a Word document from a POIFSFileSystem
     *
     * @param pfilesystem The POIFSFileSystem that contains the Word document.
     * @throws IOException If there is an unexpected IOException from the passed
     *         in POIFSFileSystem.
     */
    public HWPFDocumentCore(POIFSFileSystem pfilesystem) throws IOException {
        this(pfilesystem.getRoot());
    }

    /**
     * This constructor loads a Word document from a specific point
     *  in a POIFSFileSystem, probably not the default.
     * Used typically to open embeded documents.
     *
     * @param directory The DirectoryNode that contains the Word document.
     * @throws IOException If there is an unexpected IOException from the passed
     *         in POIFSFileSystem.
     */
    public HWPFDocumentCore(DirectoryNode directory) throws IOException {
        // Sort out the hpsf properties
        super(directory);

        // read in the main stream.
        _mainStream = getDocumentEntryBytes(STREAM_WORD_DOCUMENT, FIB_BASE_LEN, Integer.MAX_VALUE);
        _fib = new FileInformationBlock(_mainStream);

        DirectoryEntry objectPoolEntry = null;
        if (directory.hasEntry(STREAM_OBJECT_POOL)) {
            objectPoolEntry = (DirectoryEntry) directory.getEntry(STREAM_OBJECT_POOL);
        }
        _objectPool = new ObjectPoolImpl(objectPoolEntry);
    }

    /**
     * For a given named property entry, either return it or null if
     * if it wasn't found
     *
     * @param setName The property to read
     * @return The value of the given property or null if it wasn't found.
     */
    @Override
    protected PropertySet getPropertySet(String setName) {
        EncryptionInfo ei;
        try {
            ei = getEncryptionInfo();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return (ei == null)
            ? super.getPropertySet(setName)
            : super.getPropertySet(setName, ei);
    }

    protected EncryptionInfo getEncryptionInfo() throws IOException {
        if (_encryptionInfo != null) {
            return _encryptionInfo;
        }

        // Create our FIB, and check for the doc being encrypted
        byte[] fibBaseBytes = (_mainStream != null) ? _mainStream : getDocumentEntryBytes(STREAM_WORD_DOCUMENT, -1, FIB_BASE_LEN);
        FibBase fibBase = new FibBase( fibBaseBytes, 0 );
        if (!fibBase.isFEncrypted()) {
            return null;
        }

        String tableStrmName = fibBase.isFWhichTblStm() ? STREAM_TABLE_1 : STREAM_TABLE_0;
        byte[] tableStream = getDocumentEntryBytes(tableStrmName, -1, fibBase.getLKey());
        LittleEndianByteArrayInputStream leis = new LittleEndianByteArrayInputStream(tableStream);
        EncryptionMode em = fibBase.isFObfuscated() ? EncryptionMode.xor : null;
        EncryptionInfo ei = new EncryptionInfo(leis, em);
        Decryptor dec = ei.getDecryptor();
        dec.setChunkSize(512);
        try {
            String pass = Biff8EncryptionKey.getCurrentUserPassword();
            if (pass == null) {
                pass = Decryptor.DEFAULT_PASSWORD;
            }
            if (!dec.verifyPassword(pass)) {
                throw new EncryptedDocumentException("document is encrypted, password is invalid - use Biff8EncryptionKey.setCurrentUserPasswort() to set password before opening");
            }
        } catch (GeneralSecurityException e) {
            throw new IOException(e.getMessage(), e);
        }
        _encryptionInfo = ei;
        return ei;
    }

    /**
     * Reads OLE Stream into byte array - if an {@link EncryptionInfo} is available,
     * decrypt the bytes starting at encryptionOffset. If encryptionOffset = -1, then do not try
     * to decrypt the bytes
     *
     * @param name the name of the stream
     * @param encryptionOffset the offset from which to start decrypting, use {@code -1} for no decryption
     * @param len length of the bytes to be read, use {@link Integer#MAX_VALUE} for all bytes
     * @return the read bytes
     * @throws IOException if the stream can't be found
     */
    protected byte[] getDocumentEntryBytes(String name, int encryptionOffset, int len) throws IOException {
        DirectoryNode dir = getDirectory();
        DocumentEntry documentProps = (DocumentEntry)dir.getEntry(name);
        DocumentInputStream dis = dir.createDocumentInputStream(documentProps);
        EncryptionInfo ei = (encryptionOffset > -1) ? getEncryptionInfo() : null;
        int streamSize = documentProps.getSize();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(streamSize,len));

        InputStream is = dis;
        try {
            if (ei != null) {
                try {
                    Decryptor dec = ei.getDecryptor();
                    is = dec.getDataStream(dis, streamSize, 0);
                    if (encryptionOffset > 0) {
                        ChunkedCipherInputStream cis = (ChunkedCipherInputStream)is;
                        byte plain[] = new byte[encryptionOffset];
                        cis.readPlain(plain, 0, encryptionOffset);
                        bos.write(plain);
                    }
                } catch (GeneralSecurityException e) {
                    throw new IOException(e.getMessage(), e);
                }
            }
            // This simplifies a few combinations, so we actually always try to copy len bytes
            // regardless if encryptionOffset is greater than 0
            if (len < Integer.MAX_VALUE) {
                is = new BoundedInputStream(is, len);
            }
            IOUtils.copy(is, bos);
            return bos.toByteArray();
        } finally {
            IOUtils.closeQuietly(is);
            IOUtils.closeQuietly(dis);
        }
    }


    /**
     * Returns the range which covers the whole of the document, but excludes
     * any headers and footers.
     */
    public abstract Range getRange();

    /**
     * Returns the range that covers all text in the file, including main text,
     * footnotes, headers and comments
     */
    public abstract Range getOverallRange();

    /**
     * Returns document text, i.e. text information from all text pieces,
     * including OLE descriptions and field codes
     */
    public String getDocumentText() {
        return getText().toString();
    }

    /**
     * Internal method to access document text
     */
    @Internal
    public abstract StringBuilder getText();

    public CHPBinTable getCharacterTable() {
        return _cbt;
    }

    public PAPBinTable getParagraphTable() {
        return _pbt;
    }

    public SectionTable getSectionTable() {
        return _st;
    }

    public StyleSheet getStyleSheet() {
        return _ss;
    }

    public ListTables getListTables() {
        return _lt;
    }

    public FontTable getFontTable() {
        return _ft;
    }

    public FileInformationBlock getFileInformationBlock() {
        return _fib;
    }

    public ObjectsPool getObjectsPool() {
        return _objectPool;
    }

    public abstract TextPieceTable getTextTable();

    @Internal
    public byte[] getMainStream() {
        return _mainStream;
    }
}