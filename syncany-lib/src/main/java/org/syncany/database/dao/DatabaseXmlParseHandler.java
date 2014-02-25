/*
 * Syncany, www.syncany.org
 * Copyright (C) 2011-2014 Philipp C. Heckel <philipp.heckel@gmail.com> 
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.syncany.database.dao;

import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.syncany.database.ChunkEntry;
import org.syncany.database.DatabaseVersion;
import org.syncany.database.FileContent;
import org.syncany.database.FileVersion;
import org.syncany.database.MemoryDatabase;
import org.syncany.database.MultiChunkEntry;
import org.syncany.database.PartialFileHistory;
import org.syncany.database.VectorClock;
import org.syncany.database.ChunkEntry.ChunkChecksum;
import org.syncany.database.DatabaseVersionHeader.DatabaseVersionType;
import org.syncany.database.FileContent.FileChecksum;
import org.syncany.database.FileVersion.FileStatus;
import org.syncany.database.FileVersion.FileType;
import org.syncany.database.MultiChunkEntry.MultiChunkId;
import org.syncany.database.PartialFileHistory.FileHistoryId;
import org.syncany.database.VectorClock.VectorClockComparison;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * @author pheckel
 *
 */
public class DatabaseXmlParseHandler extends DefaultHandler {
	private static final Logger logger = Logger.getLogger(DatabaseXmlParseHandler.class.getSimpleName());
	
	private MemoryDatabase database;
	private VectorClock versionFrom;
	private VectorClock versionTo;
	private boolean headersOnly;

	private String elementPath;
	private DatabaseVersion databaseVersion;
	private VectorClock vectorClock;
	private boolean vectorClockInLoadRange;
	private FileContent fileContent;
	private MultiChunkEntry multiChunk;
	private PartialFileHistory fileHistory;
	
	public DatabaseXmlParseHandler(MemoryDatabase database, VectorClock fromVersion, VectorClock toVersion, boolean headersOnly) {
		this.elementPath = "";
		this.database = database;
		this.versionFrom = fromVersion;
		this.versionTo = toVersion;
		this.headersOnly = headersOnly;
	}
	
	@Override
	public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
		elementPath += "/"+qName;
		
		if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion")) {				
			databaseVersion = new DatabaseVersion();				
		}			
		else if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion/header/type")) {
			String typeStr = attributes.getValue("value");
			databaseVersion.getHeader().setType(DatabaseVersionType.valueOf(typeStr));
		}
		else if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion/header/time")) {
			Date timeValue = new Date(Long.parseLong(attributes.getValue("value")));
			databaseVersion.setTimestamp(timeValue);
		}
		else if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion/header/client")) {
			String clientName = attributes.getValue("name");
			databaseVersion.setClient(clientName);
		}
		else if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion/header/vectorClock")) {
			vectorClock = new VectorClock();
		}
		else if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion/header/vectorClock/client")) {
			String clientName = attributes.getValue("name");
			Long clientValue = Long.parseLong(attributes.getValue("value"));
			
			vectorClock.setClock(clientName, clientValue);
		}		
		else if (!headersOnly) {
			if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion/chunks/chunk")) {
				String chunkChecksumStr = attributes.getValue("checksum");
				ChunkChecksum chunkChecksum = ChunkChecksum.parseChunkChecksum(chunkChecksumStr);
				int chunkSize = Integer.parseInt(attributes.getValue("size"));
				
				ChunkEntry chunkEntry = new ChunkEntry(chunkChecksum, chunkSize);
				databaseVersion.addChunk(chunkEntry);
			}
			else if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion/fileContents/fileContent")) {
				String checksumStr = attributes.getValue("checksum");
				long size = Long.parseLong(attributes.getValue("size"));

				fileContent = new FileContent();
				fileContent.setChecksum(FileChecksum.parseFileChecksum(checksumStr));
				fileContent.setSize(size);							
			}
			else if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion/fileContents/fileContent/chunkRefs/chunkRef")) {
				String chunkChecksumStr = attributes.getValue("ref");

				fileContent.addChunk(ChunkChecksum.parseChunkChecksum(chunkChecksumStr));
			}
			else if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion/multiChunks/multiChunk")) {
				String multChunkIdStr = attributes.getValue("id");
				MultiChunkId multiChunkId = MultiChunkId.parseMultiChunkId(multChunkIdStr);							
				
				if (multiChunkId == null) {
					throw new SAXException("Cannot read ID from multichunk " + multChunkIdStr);
				}
				
				multiChunk = new MultiChunkEntry(multiChunkId);					
			}			
			else if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion/multiChunks/multiChunk/chunkRefs/chunkRef")) {
				String chunkChecksumStr = attributes.getValue("ref");

				multiChunk.addChunk(ChunkChecksum.parseChunkChecksum(chunkChecksumStr));
			}
			else if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion/fileHistories/fileHistory")) {
				String fileHistoryIdStr = attributes.getValue("id");
				FileHistoryId fileId = FileHistoryId.parseFileId(fileHistoryIdStr);
				fileHistory = new PartialFileHistory(fileId);
			}	
			else if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion/fileHistories/fileHistory/fileVersions/fileVersion")) {
				String fileVersionStr = attributes.getValue("version");
				String path = attributes.getValue("path");
				String sizeStr = attributes.getValue("size");
				String typeStr = attributes.getValue("type");
				String statusStr = attributes.getValue("status");
				String lastModifiedStr = attributes.getValue("lastModified");
				String updatedStr = attributes.getValue("updated");
				String checksumStr = attributes.getValue("checksum");
				String linkTarget = attributes.getValue("linkTarget");
				String dosAttributes = attributes.getValue("dosattrs");
				String posixPermissions = attributes.getValue("posixperms");
				
				if (fileVersionStr == null || path == null || typeStr == null || statusStr == null || sizeStr == null || lastModifiedStr == null) {
					throw new SAXException("FileVersion: Attributes missing: version, path, type, status, size and last modified are mandatory");
				}
				
				FileVersion fileVersion = new FileVersion();
				 
				fileVersion.setVersion(Long.parseLong(fileVersionStr));
				fileVersion.setPath(path);
				fileVersion.setType(FileType.valueOf(typeStr));
				fileVersion.setStatus(FileStatus.valueOf(statusStr));
				fileVersion.setSize(Long.parseLong(sizeStr));				
				fileVersion.setLastModified(new Date(Long.parseLong(lastModifiedStr)));
				
				if (updatedStr != null) {
					fileVersion.setUpdated(new Date(Long.parseLong(updatedStr)));
				}
				
				if (checksumStr != null) {
					fileVersion.setChecksum(FileChecksum.parseFileChecksum(checksumStr));							
				}
				
				if (linkTarget != null) {
					fileVersion.setLinkTarget(linkTarget);
				}
				
				if (dosAttributes != null) {
					fileVersion.setDosAttributes(dosAttributes);
				}
				
				if (posixPermissions != null) {
					fileVersion.setPosixPermissions(posixPermissions);
				}

				fileHistory.addFileVersion(fileVersion);							
			}			
		}
	}
	
	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion")) {
			if (vectorClockInLoadRange) {
				database.addDatabaseVersion(databaseVersion);
				logger.log(Level.INFO, "   + Added database version "+databaseVersion.getHeader());
			}
			else {
				logger.log(Level.INFO, "   + Ignoring database version "+databaseVersion.getHeader()+", not in load range: "+versionFrom+" - "+versionTo);
			}

			databaseVersion = null;
		}	
		else if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion/header/vectorClock")) {				
			vectorClockInLoadRange = vectorClockInRange(vectorClock, versionFrom, versionTo);
			
			databaseVersion.setVectorClock(vectorClock);
			vectorClock = null;
		}
		else if (!headersOnly) {
			if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion/fileContents/fileContent")) {
				databaseVersion.addFileContent(fileContent);
				fileContent = null;
			}	
			else if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion/multiChunks/multiChunk")) {
				databaseVersion.addMultiChunk(multiChunk);
				multiChunk = null;
			}	
			else if (elementPath.equalsIgnoreCase("/database/databaseVersions/databaseVersion/fileHistories/fileHistory")) {
				databaseVersion.addFileHistory(fileHistory);
				fileHistory = null;
			}	
			else {
				//System.out.println("NO MATCH");
			}
		}
		
		elementPath = elementPath.substring(0, elementPath.lastIndexOf("/"));					
	}
			
	@Override
	public void characters(char[] ch, int start, int length) throws SAXException {
		// Nothing	
	}	
	

	private boolean vectorClockInRange(VectorClock vectorClock, VectorClock vectorClockRangeFrom, VectorClock vectorClockRangeTo) {
		// Determine if: versionFrom < databaseVersion
		boolean greaterOrEqualToVersionFrom = false;

		if (vectorClockRangeFrom == null) {
			greaterOrEqualToVersionFrom = true;
		}
		else {
			VectorClockComparison comparison = VectorClock.compare(vectorClockRangeFrom, vectorClock);
			
			if (comparison == VectorClockComparison.EQUAL || comparison == VectorClockComparison.SMALLER) {
				greaterOrEqualToVersionFrom = true;
			}				
		}
		
		// Determine if: databaseVersion < versionTo
		boolean lowerOrEqualToVersionTo = false;

		if (vectorClockRangeTo == null) {
			lowerOrEqualToVersionTo = true;
		}
		else {
			VectorClockComparison comparison = VectorClock.compare(vectorClock, vectorClockRangeTo);
			
			if (comparison == VectorClockComparison.EQUAL || comparison == VectorClockComparison.SMALLER) {
				lowerOrEqualToVersionTo = true;
			}				
		}

		return greaterOrEqualToVersionFrom && lowerOrEqualToVersionTo;		
	}	
}