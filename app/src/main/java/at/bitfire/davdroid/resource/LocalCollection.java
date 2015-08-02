/*
 * Copyright © 2013 – 2015 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */
package at.bitfire.davdroid.resource;

import android.accounts.Account;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderOperation.Builder;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.CalendarContract;
import android.util.Log;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import lombok.Cleanup;

/**
 * Represents a locally-stored synchronizable collection (for instance, the
 * address book or a calendar). Manages a CTag that stores the last known
 * remote CTag (the remote CTag changes whenever something in the remote collection changes).
 * 
 * @param <T> Subtype of Resource that can be stored in the collection 
 */
public abstract class LocalCollection<T extends Resource> {
	private static final String TAG = "davdroid.Collection";
	
	final protected Account account;
	final protected ContentProviderClient providerClient;
	final protected ArrayList<ContentProviderOperation> pendingOperations = new ArrayList<>();

	
	// database fields
	
	/** base Uri of the collection's entries (for instance, Events.CONTENT_URI);
	 *  apply syncAdapterURI() before returning a value */
	abstract protected Uri entriesURI();

	/** column name of the type of the account the entry belongs to */
	abstract protected String entryColumnAccountType();
	/** column name of the name of the account the entry belongs to */
	abstract protected String entryColumnAccountName();
	
	/** column name of the collection ID the entry belongs to */
	abstract protected String entryColumnParentID();
	/** column name of an entry's ID */
	abstract protected String entryColumnID();
	/** column name of an entry's file name on the WebDAV server */
	abstract protected String entryColumnRemoteName();
	/** column name of an entry's last ETag on the WebDAV server; null if entry hasn't been uploaded yet */
	abstract protected String entryColumnETag();
	
	/** column name of an entry's "dirty" flag (managed by content provider) */
	abstract protected String entryColumnDirty();
	/** column name of an entry's "deleted" flag (managed by content provider) */
	abstract protected String entryColumnDeleted();
	
	/** column name of an entry's UID */
	abstract protected String entryColumnUID();


	/** ID of the collection (for instance, CalendarContract.Calendars._ID) */
	// protected long id;

	/** SQL filter expression */
	String sqlFilter;
	

	LocalCollection(Account account, ContentProviderClient providerClient) {
		this.account = account;
		this.providerClient = providerClient;
	}
	

	// collection operations
	
	/** gets the ID if the collection (for instance, ID of the Android calendar) */
	abstract public long getId();
	/** sets local stored CTag */
	abstract public void setCTag(String cTag) throws LocalStorageException;
	/** gets the CTag of the collection */
	abstract public String getCTag() throws LocalStorageException;
	/** update locally stored collection properties */
	abstract public void updateMetaData(String displayName, String color) throws LocalStorageException;

	
	// content provider (= database) querying

	/**
	 * Finds new resources (resources which haven't been uploaded yet).
	 * New resources are 1) dirty, and 2) don't have an ETag yet.
	 * Only records matching sqlFilter will be returned.
	 * 
	 * @return IDs of new resources
	 * @throws LocalStorageException when the content provider couldn't be queried
	 */
	public long[] findNew() throws LocalStorageException {
		String where = entryColumnDirty() + "=1 AND " + entryColumnETag() + " IS NULL";
		if (entryColumnParentID() != null)
			where += " AND " + entryColumnParentID() + "=" + String.valueOf(getId());
		if (sqlFilter != null)
			where += " AND (" + sqlFilter + ")";
		try {
			@Cleanup Cursor cursor = providerClient.query(entriesURI(),
					new String[] { entryColumnID() },
					where, null, null);
			if (cursor == null)
				throw new LocalStorageException("Couldn't query new records");
			
			long[] fresh = new long[cursor.getCount()];
			for (int idx = 0; cursor.moveToNext(); idx++) {
				long id = cursor.getLong(0);
				
				// new record: we have to generate UID + remote file name for uploading
				T resource = findById(id, false);
				resource.initialize();
				// write generated UID + remote file name into database
				ContentValues values = new ContentValues(2);
				values.put(entryColumnUID(), resource.getUid());
				values.put(entryColumnRemoteName(), resource.getName());
				providerClient.update(ContentUris.withAppendedId(entriesURI(), id), values, null, null);
				
				fresh[idx] = id;
			}
			return fresh;
		} catch(RemoteException ex) {
			throw new LocalStorageException(ex);
		}
	}
	
	/**
	 * Finds updated resources (resources which have already been uploaded, but have changed locally).
	 * Updated resources are 1) dirty, and 2) already have an ETag. Only records matching sqlFilter
	 * will be returned.
	 * 
	 * @return IDs of updated resources
	 * @throws LocalStorageException when the content provider couldn't be queried
	 */
	public long[] findUpdated() throws LocalStorageException {
		String where = entryColumnDirty() + "=1 AND " + entryColumnETag() + " IS NOT NULL";
		if (entryColumnParentID() != null)
			where += " AND " + entryColumnParentID() + "=" + String.valueOf(getId());
		if (sqlFilter != null)
			where += " AND (" + sqlFilter + ")";
		try {
			@Cleanup Cursor cursor = providerClient.query(entriesURI(),
					new String[] { entryColumnID(), entryColumnRemoteName(), entryColumnETag() },
					where, null, null);
			if (cursor == null)
				throw new LocalStorageException("Couldn't query updated records");
			
			long[] updated = new long[cursor.getCount()];
			for (int idx = 0; cursor.moveToNext(); idx++)
				updated[idx] = cursor.getLong(0);
			return updated;
		} catch(RemoteException ex) {
			throw new LocalStorageException(ex);
		}
	}

	/**
	 * Finds deleted resources (resources which have been marked for deletion).
	 * Deleted resources have the "deleted" flag set.
	 * Only records matching sqlFilter will be returned.
	 * 
	 * @return IDs of deleted resources
	 * @throws LocalStorageException when the content provider couldn't be queried
	 */
	public long[] findDeleted() throws LocalStorageException {
		String where = entryColumnDeleted() + "=1";
		if (entryColumnParentID() != null)
			where += " AND " + entryColumnParentID() + "=" + String.valueOf(getId());
		if (sqlFilter != null)
			where += " AND (" + sqlFilter + ")";
		try {
			@Cleanup Cursor cursor = providerClient.query(entriesURI(),
					new String[] { entryColumnID(), entryColumnRemoteName(), entryColumnETag() },
					where, null, null);
			if (cursor == null)
				throw new LocalStorageException("Couldn't query dirty records");
			
			long deleted[] = new long[cursor.getCount()];
			for (int idx = 0; cursor.moveToNext(); idx++)
				deleted[idx] = cursor.getLong(0);
			return deleted;
		} catch(RemoteException ex) {
			throw new LocalStorageException(ex);
		}
	}
	
	/**
	 * Finds a specific resource by ID. Only records matching sqlFilter are taken into account.
	 * @param localID	ID of the resource
	 * @param populate	true: populates all data fields (for instance, contact or event details);
	 * 					false: only remote file name and ETag are populated
	 * @return resource with either ID/remote file/name/ETag or all fields populated
	 * @throws RecordNotFoundException when the resource couldn't be found
	 * @throws LocalStorageException when the content provider couldn't be queried
	 */
	public T findById(long localID, boolean populate) throws LocalStorageException {
		try {
			@Cleanup Cursor cursor = providerClient.query(ContentUris.withAppendedId(entriesURI(), localID),
					new String[] { entryColumnRemoteName(), entryColumnETag() }, sqlFilter, null, null);
			if (cursor != null && cursor.moveToNext()) {
				T resource = newResource(localID, cursor.getString(0), cursor.getString(1));
				if (populate)
					populate(resource);
				return resource;
			} else
				throw new RecordNotFoundException();
		} catch(RemoteException ex) {
			throw new LocalStorageException(ex);
		}
	}
	
	/**
	 * Finds a specific resource by remote file name. Only records matching sqlFilter are taken into account.
	 * @param remoteName	remote file name of the resource
	 * @param populate	    true: populates all data fields (for instance, contact or event details);
	 * 					    false: only remote file name and ETag are populated
	 * @return resource with either ID/remote file/name/ETag or all fields populated
	 * @throws RecordNotFoundException when the resource couldn't be found
	 * @throws LocalStorageException when the content provider couldn't be queried
	 */
	public T findByRemoteName(String remoteName, boolean populate) throws LocalStorageException {
		String where = entryColumnRemoteName() + "=?";
		if (sqlFilter != null)
			where += " AND (" + sqlFilter + ")";
		try {
			@Cleanup Cursor cursor = providerClient.query(entriesURI(),
					new String[] { entryColumnID(), entryColumnRemoteName(), entryColumnETag() },
					where, new String[] { remoteName }, null);
			if (cursor != null && cursor.moveToNext()) {
				T resource = newResource(cursor.getLong(0), cursor.getString(1), cursor.getString(2));
				if (populate)
					populate(resource);
				return resource;
			} else
				throw new RecordNotFoundException();
		} catch(RemoteException ex) {
			throw new LocalStorageException(ex);
		}
	}

	/** populates all data fields from the content provider */
	public abstract void populate(Resource record) throws LocalStorageException;

	
	// create/update/delete
	
	/**
	 * Creates a new resource object in memory. No content provider operations involved.
	 * @param localID the ID of the resource
	 * @param resourceName the (remote) file name of the resource
	 * @param eTag ETag of the resource
	 * @return the new resource object */
	abstract public T newResource(long localID, String resourceName, String eTag);
	
	/** Adds the resource (including all data) to the local collection.
	 * @param resource   Resource to be added
	 */
	public void add(Resource resource) throws LocalStorageException {
		int idx = pendingOperations.size();
		pendingOperations.add(
				buildEntry(ContentProviderOperation.newInsert(entriesURI()), resource, false)
						.withYieldAllowed(true)
						.build());
		
		addDataRows(resource, -1, idx);
        commit();
	}
	
	/** Updates an existing resource in the local collection. The resource will be found by
	 * the remote file name and all data will be updated. */
	public void updateByRemoteName(Resource remoteResource) throws LocalStorageException {
		T localResource = findByRemoteName(remoteResource.getName(), false);
		pendingOperations.add(
				buildEntry(ContentProviderOperation.newUpdate(ContentUris.withAppendedId(entriesURI(), localResource.getLocalID())), remoteResource, true)
				.withValue(entryColumnETag(), remoteResource.getETag())
				.withYieldAllowed(true)
				.build());
		
		removeDataRows(localResource);
		addDataRows(remoteResource, localResource.getLocalID(), -1);
        commit();
	}

	/** Enqueues deleting a resource from the local collection. Requires commit() to be effective! */
	public void delete(Resource resource) {
		pendingOperations.add(ContentProviderOperation
				.newDelete(ContentUris.withAppendedId(entriesURI(), resource.getLocalID()))
				.withYieldAllowed(true)
				.build());
	}

	/**
	 * Deletes all resources except the give ones from the local collection.
	 * @param remoteResources resources with these remote file names will be kept
     * @return number of deleted resources
	 */
	public int deleteAllExceptRemoteNames(Resource[] remoteResources) throws LocalStorageException
	{
		final String where;

		if (remoteResources.length != 0) {
			// delete all except certain entries
			final List<String> sqlFileNames = new LinkedList<>();
			for (final Resource res : remoteResources)
				sqlFileNames.add(DatabaseUtils.sqlEscapeString(res.getName()));
			where = entryColumnRemoteName() + " NOT IN (" + StringUtils.join(sqlFileNames, ",") + ')';
		} else
			// delete all entries
			where = entryColumnRemoteName() + " IS NOT NULL";

        try {
	        if (entryColumnParentID() != null)
		        // entries have a parent collection (for instance, events which have a calendar)
	            return providerClient.delete(
	                    entriesURI(),
	                    entryColumnParentID() + "=? AND (" + where + ')',   // restrict deletion to parent collection
	                    new String[] { String.valueOf(getId()) }
	            );
	        else
	            // entries don't have a parent collection (contacts are stored directly and not within an address book)
		        return providerClient.delete(entriesURI(), where, null);
        } catch (RemoteException e) {
            throw new LocalStorageException("Couldn't delete local resources", e);
        }
    }


	/** Updates the locally-known ETag of a resource. */
	public void updateETag(Resource res, String eTag) throws LocalStorageException {
		Log.d(TAG, "Setting ETag of local resource " + res.getName() + " to " + eTag);
		
		ContentValues values = new ContentValues(1);
		values.put(entryColumnETag(), eTag);
		try {
			providerClient.update(ContentUris.withAppendedId(entriesURI(), res.getLocalID()), values, null, new String[] {});
		} catch (RemoteException e) {
			throw new LocalStorageException(e);
		}
	}
	
	/** Enqueues removing the dirty flag from a locally-stored resource. Requires commit() to be effective! */
	public void clearDirty(Resource resource) {
		pendingOperations.add(ContentProviderOperation
				.newUpdate(ContentUris.withAppendedId(entriesURI(), resource.getLocalID()))
				.withValue(entryColumnDirty(), 0)
				.build());
	}

	/** Commits enqueued operations to the content provider (for batch operations). */
	public int commit() throws LocalStorageException {
        int affected = 0;
		if (!pendingOperations.isEmpty())
			try {
				Log.d(TAG, "Committing " + pendingOperations.size() + " operations ...");
                ContentProviderResult[] results = providerClient.applyBatch(pendingOperations);
                for (ContentProviderResult result : results)
                    if (result != null)                 // will have either .uri or .count set
						if (result.count != null)
                            affected += result.count;
						else if (result.uri != null)
							affected = 1;
                Log.d(TAG, "... " + affected + " record(s) affected");
				pendingOperations.clear();
			} catch(OperationApplicationException | RemoteException ex) {
				throw new LocalStorageException(ex);
			}
        return affected;
	}

	
	// helpers

	protected void queueOperation(Builder builder) {
		if (builder != null)
			pendingOperations.add(builder.build());
	}

	/** Appends account type, name and CALLER_IS_SYNCADAPTER to an Uri. */
	protected Uri syncAdapterURI(Uri baseURI) {
		return baseURI.buildUpon()
				.appendQueryParameter(entryColumnAccountType(), account.type)
				.appendQueryParameter(entryColumnAccountName(), account.name)
				.appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
				.build();
	}
	
	protected Builder newDataInsertBuilder(Uri dataUri, String refFieldName, long raw_ref_id, int backrefIdx) {
		Builder builder = ContentProviderOperation.newInsert(syncAdapterURI(dataUri));
		if (backrefIdx != -1)
			return builder.withValueBackReference(refFieldName, backrefIdx);
		else
			return builder.withValue(refFieldName, raw_ref_id);
	}
	
	
	// content builders

	/**
	 * Builds the main entry (for instance, a ContactsContract.RawContacts row) from a resource.
	 * The entry is built for insertion to the location identified by entriesURI().
	 * 
	 * @param builder   Builder to be extended by all resource data that can be stored without extra data rows.
	 * @param resource  Event, task or note resource whose contents shall be inserted/updated
	 * @param update    false when the entry is built the first time (when creating the row), true if it's an update
	 */
	protected abstract Builder buildEntry(Builder builder, Resource resource, boolean update);
	
	/** Enqueues adding extra data rows of the resource to the local collection. */
	protected abstract void addDataRows(Resource resource, long localID, int backrefIdx);
	
	/** Enqueues removing all extra data rows of the resource from the local collection. */
	protected abstract void removeDataRows(Resource resource);
}
