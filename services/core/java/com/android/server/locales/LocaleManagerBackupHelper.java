/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.locales;

import static android.os.UserHandle.USER_NULL;

import static com.android.server.locales.LocaleManagerService.DEBUG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.backup.BackupManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.BestClock;
import android.os.Binder;
import android.os.Environment;
import android.os.HandlerThread;
import android.os.LocaleList;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.XmlUtils;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper class for managing backup and restore of app-specific locales.
 */
class LocaleManagerBackupHelper {
    private static final String TAG = "LocaleManagerBkpHelper"; // must be < 23 chars

    // Tags and attributes for xml.
    private static final String LOCALES_XML_TAG = "locales";
    private static final String PACKAGE_XML_TAG = "package";
    private static final String ATTR_PACKAGE_NAME = "name";
    private static final String ATTR_LOCALES = "locales";
    private static final String ATTR_CREATION_TIME_MILLIS = "creationTimeMillis";

    private static final String STAGE_FILE_NAME = "staged_locales";
    private static final String SYSTEM_BACKUP_PACKAGE_KEY = "android";

    private static final Pattern STAGE_FILE_NAME_PATTERN = Pattern.compile(
            TextUtils.formatSimple("(^%s_)(\\d+)(\\.xml$)", STAGE_FILE_NAME));
    private static final int USER_ID_GROUP_INDEX_IN_PATTERN = 2;
    private static final Duration STAGE_FILE_RETENTION_PERIOD = Duration.ofDays(3);

    private final LocaleManagerService mLocaleManagerService;
    private final PackageManagerInternal mPackageManagerInternal;
    private final File mStagedLocalesDir;
    private final Clock mClock;
    private final Context mContext;
    private final Object mStagedDataLock = new Object();

    // Staged data map keyed by user-id to handle multi-user scenario / work profiles. We are using
    // SparseArray because it is more memory-efficient than a HashMap.
    private final SparseArray<StagedData> mStagedData = new SparseArray<>();

    private final PackageMonitor mPackageMonitor;
    private final BroadcastReceiver mUserMonitor;

    LocaleManagerBackupHelper(LocaleManagerService localeManagerService,
            PackageManagerInternal pmInternal) {
        this(localeManagerService.mContext, localeManagerService, pmInternal,
                new File(Environment.getDataSystemCeDirectory(),
                        "app_locales"), getDefaultClock());
    }

    private static @NonNull Clock getDefaultClock() {
        return new BestClock(ZoneOffset.UTC, SystemClock.currentNetworkTimeClock(),
                Clock.systemUTC());
    }

    @VisibleForTesting LocaleManagerBackupHelper(Context context,
            LocaleManagerService localeManagerService,
            PackageManagerInternal pmInternal, File stagedLocalesDir, Clock clock) {
        mContext = context;
        mLocaleManagerService = localeManagerService;
        mPackageManagerInternal = pmInternal;
        mClock = clock;
        mStagedLocalesDir = stagedLocalesDir;

        loadAllStageFiles();

        HandlerThread broadcastHandlerThread = new HandlerThread(TAG,
                Process.THREAD_PRIORITY_BACKGROUND);
        broadcastHandlerThread.start();

        mPackageMonitor = new PackageMonitorImpl();
        mPackageMonitor.register(context, broadcastHandlerThread.getLooper(),
                UserHandle.ALL,
                true);
        mUserMonitor = new UserMonitor();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_REMOVED);
        context.registerReceiverAsUser(mUserMonitor, UserHandle.ALL, filter,
                null, broadcastHandlerThread.getThreadHandler());
    }

    @VisibleForTesting
    BroadcastReceiver getUserMonitor() {
        return mUserMonitor;
    }

    @VisibleForTesting
    PackageMonitor getPackageMonitor() {
        return mPackageMonitor;
    }

    /**
     * Loads the staged data into memory by reading all the files in the staged directory.
     *
     * <p><b>Note:</b> We don't ned to hold the lock here because this is only called in the
     * constructor (before any broadcast receivers are registered).
     */
    private void loadAllStageFiles() {
        File[] files = mStagedLocalesDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String fileName = file.getName();
            Matcher matcher = STAGE_FILE_NAME_PATTERN.matcher(fileName);
            if (!matcher.matches()) {
                file.delete();
                Slog.w(TAG, TextUtils.formatSimple("Deleted %s. Reason: %s.", fileName,
                        "Unrecognized file"));
                continue;
            }
            try {
                final int userId = Integer.parseInt(matcher.group(USER_ID_GROUP_INDEX_IN_PATTERN));
                StagedData stagedData = readStageFile(file);
                if (stagedData != null) {
                    mStagedData.put(userId, stagedData);
                } else {
                    file.delete();
                    Slog.w(TAG, TextUtils.formatSimple("Deleted %s. Reason: %s.", fileName,
                            "Could not read file"));
                }
            } catch (NumberFormatException e) {
                file.delete();
                Slog.w(TAG, TextUtils.formatSimple("Deleted %s. Reason: %s.", fileName,
                        "Could not parse user id from file name"));
            }
        }
    }

    /**
     * Loads the stage file from the disk and parses it into a list of app backups.
     */
    private @Nullable StagedData readStageFile(@NonNull File file) {
        InputStream stagedDataInputStream = null;
        AtomicFile stageFile = new AtomicFile(file);
        try {
            stagedDataInputStream = stageFile.openRead();
            final TypedXmlPullParser parser = Xml.newFastPullParser();
            parser.setInput(stagedDataInputStream, StandardCharsets.UTF_8.name());

            XmlUtils.beginDocument(parser, LOCALES_XML_TAG);
            long creationTimeMillis = parser.getAttributeLong(/* namespace= */ null,
                    ATTR_CREATION_TIME_MILLIS);
            return new StagedData(creationTimeMillis, readFromXml(parser));
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Could not parse stage file ", e);
        } finally {
            IoUtils.closeQuietly(stagedDataInputStream);
        }
        return null;
    }

    /**
     * @see LocaleManagerInternal#getBackupPayload(int userId)
     */
    public byte[] getBackupPayload(int userId) {
        if (DEBUG) {
            Slog.d(TAG, "getBackupPayload invoked for user id " + userId);
        }

        synchronized (mStagedDataLock) {
            cleanStagedDataForOldEntriesLocked();
        }

        HashMap<String, String> pkgStates = new HashMap<>();
        for (ApplicationInfo appInfo : mPackageManagerInternal.getInstalledApplications(/*flags*/0,
                userId, Binder.getCallingUid())) {
            try {
                LocaleList appLocales = mLocaleManagerService.getApplicationLocales(
                        appInfo.packageName,
                        userId);
                // Backup locales only for apps which do have app-specific overrides.
                if (!appLocales.isEmpty()) {
                    if (DEBUG) {
                        Slog.d(TAG, "Add package=" + appInfo.packageName + " locales="
                                + appLocales.toLanguageTags() + " to backup payload");
                    }
                    pkgStates.put(appInfo.packageName, appLocales.toLanguageTags());
                }
            } catch (RemoteException | IllegalArgumentException e) {
                Slog.e(TAG, "Exception when getting locales for package: " + appInfo.packageName,
                        e);
            }
        }

        if (pkgStates.isEmpty()) {
            if (DEBUG) {
                Slog.d(TAG, "Final payload=null");
            }
            // Returning null here will ensure deletion of the entry for LMS from the backup data.
            return null;
        }

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            // Passing arbitrary value for creationTimeMillis since it is ignored when forStage
            // is false.
            writeToXml(out, pkgStates, /* forStage= */ false, /* creationTimeMillis= */ -1);
        } catch (IOException e) {
            Slog.e(TAG, "Could not write to xml for backup ", e);
            return null;
        }

        if (DEBUG) {
            try {
                Slog.d(TAG, "Final payload=" + out.toString("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                Slog.w(TAG, "Could not encode payload to UTF-8", e);
            }
        }
        return out.toByteArray();
    }

    private void cleanStagedDataForOldEntriesLocked() {
        for (int i = 0; i < mStagedData.size(); i++) {
            int userId = mStagedData.keyAt(i);
            StagedData stagedData = mStagedData.get(userId);
            if (stagedData.mCreationTimeMillis
                    < mClock.millis() - STAGE_FILE_RETENTION_PERIOD.toMillis()) {
                deleteStagedDataLocked(userId);
            }
        }
    }

    /**
     * @see LocaleManagerInternal#stageAndApplyRestoredPayload(byte[] payload, int userId)
     */
    public void stageAndApplyRestoredPayload(byte[] payload, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "stageAndApplyRestoredPayload user=" + userId + " payload="
                    + (payload != null ? new String(payload, StandardCharsets.UTF_8) : null));
        }
        if (payload == null) {
            Slog.e(TAG, "stageAndApplyRestoredPayload: no payload to restore for user " + userId);
            return;
        }

        final ByteArrayInputStream inputStream = new ByteArrayInputStream(payload);

        HashMap<String, String> pkgStates = new HashMap<>();
        try {
            // Parse the input blob into a list of BackupPackageState.
            final TypedXmlPullParser parser = Xml.newFastPullParser();
            parser.setInput(inputStream, StandardCharsets.UTF_8.name());

            XmlUtils.beginDocument(parser, LOCALES_XML_TAG);
            pkgStates = readFromXml(parser);
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Could not parse payload ", e);
        }

        // We need a lock here to prevent race conditions when accessing the stage file.
        // It might happen that a restore was triggered (manually using bmgr cmd) and at the same
        // time a new package is added. We want to ensure that both these operations aren't
        // performed simultaneously.
        synchronized (mStagedDataLock) {
            // Backups for apps which are yet to be installed.
            mStagedData.put(userId, new StagedData(mClock.millis(), new HashMap<>()));

            for (String pkgName : pkgStates.keySet()) {
                String languageTags = pkgStates.get(pkgName);
                // Check if the application is already installed for the concerned user.
                if (isPackageInstalledForUser(pkgName, userId)) {
                    // Don't apply the restore if the locales have already been set for the app.
                    checkExistingLocalesAndApplyRestore(pkgName, languageTags, userId);
                } else {
                    // Stage the data if the app isn't installed.
                    mStagedData.get(userId).mPackageStates.put(pkgName, languageTags);
                    if (DEBUG) {
                        Slog.d(TAG, "Add locales=" + languageTags
                                + " package=" + pkgName + " for lazy restore.");
                    }
                }
            }

            writeStageFileLocked(userId);
        }
    }

    /**
     * Notifies the backup manager to include the "android" package in the next backup pass.
     */
    public void notifyBackupManager() {
        BackupManager.dataChanged(SYSTEM_BACKUP_PACKAGE_KEY);
    }

    private boolean isPackageInstalledForUser(String packageName, int userId) {
        PackageInfo pkgInfo = null;
        try {
            pkgInfo = mContext.getPackageManager().getPackageInfoAsUser(
                    packageName, /* flags= */ 0, userId);
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) {
                Slog.d(TAG, "Could not get package info for " + packageName, e);
            }
        }
        return pkgInfo != null;
    }

    /**
     * Checks if locales already exist for the application and applies the restore accordingly.
     * <p>
     * The user might change the locales for an application before the restore is applied. In this
     * case, we want to keep the user settings and discard the restore.
     */
    private void checkExistingLocalesAndApplyRestore(@NonNull String pkgName,
            @NonNull String languageTags, int userId) {
        try {
            LocaleList currLocales = mLocaleManagerService.getApplicationLocales(
                    pkgName,
                    userId);
            if (!currLocales.isEmpty()) {
                return;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not check for current locales before restoring", e);
        }

        // Restore the locale immediately
        try {
            mLocaleManagerService.setApplicationLocales(pkgName, userId,
                    LocaleList.forLanguageTags(languageTags));
            if (DEBUG) {
                Slog.d(TAG, "Restored locales=" + languageTags + " for package=" + pkgName);
            }
        } catch (RemoteException | IllegalArgumentException e) {
            Slog.e(TAG, "Could not restore locales for " + pkgName, e);
        }
    }

    /**
     * Converts the list of app backups into xml and writes it onto the disk.
     */
    private void writeStageFileLocked(int userId) {
        StagedData stagedData = mStagedData.get(userId);
        if (stagedData.mPackageStates.isEmpty()) {
            deleteStagedDataLocked(userId);
            return;
        }

        final FileOutputStream stagedDataOutputStream;
        AtomicFile stageFile = new AtomicFile(
                new File(mStagedLocalesDir,
                        TextUtils.formatSimple("%s_%d.xml", STAGE_FILE_NAME, userId)));
        try {
            stagedDataOutputStream = stageFile.startWrite();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to save stage file");
            return;
        }

        try {
            writeToXml(stagedDataOutputStream, stagedData.mPackageStates,  /* forStage= */ true,
                    stagedData.mCreationTimeMillis);
            stageFile.finishWrite(stagedDataOutputStream);
            if (DEBUG) {
                Slog.d(TAG, "Stage file written.");
            }
        } catch (IOException e) {
            Slog.e(TAG, "Could not write stage file", e);
            stageFile.failWrite(stagedDataOutputStream);
        }
    }

    private void deleteStagedDataLocked(@UserIdInt int userId) {
        AtomicFile stageFile = getStageFileIfExistsLocked(userId);
        if (stageFile != null) {
            stageFile.delete();
        }
        mStagedData.remove(userId);
    }

    private @Nullable AtomicFile getStageFileIfExistsLocked(@UserIdInt int userId) {
        final File stageFile = new File(mStagedLocalesDir,
                TextUtils.formatSimple("%s_%d.xml", STAGE_FILE_NAME, userId));
        return stageFile.isFile() ? new AtomicFile(stageFile)
                : null;
    }

    /**
     * Parses the backup data from the serialized xml input stream.
     */
    private @NonNull HashMap<String, String> readFromXml(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        HashMap<String, String> packageStates = new HashMap<>();
        int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            if (parser.getName().equals(PACKAGE_XML_TAG)) {
                String packageName = parser.getAttributeValue(/* namespace= */ null,
                        ATTR_PACKAGE_NAME);
                String languageTags = parser.getAttributeValue(/* namespace= */ null, ATTR_LOCALES);

                if (!TextUtils.isEmpty(packageName) && !TextUtils.isEmpty(languageTags)) {
                    packageStates.put(packageName, languageTags);
                }
            }
        }
        return packageStates;
    }

    /**
     * Converts the list of app backup data into a serialized xml stream.
     *
     * @param forStage Flag to indicate whether this method is called for the purpose of
     * staging the data. Note that if this is false, {@code creationTimeMillis} is ignored because
     * we only need it for the stage data.
     * @param creationTimeMillis The timestamp when the stage data was created. This is required
     * to determine when to delete the stage data.
     */
    private static void writeToXml(OutputStream stream,
            @NonNull HashMap<String, String> pkgStates, boolean forStage, long creationTimeMillis)
            throws IOException {
        if (pkgStates.isEmpty()) {
            // No need to write anything at all if pkgStates is empty.
            return;
        }

        TypedXmlSerializer out = Xml.newFastSerializer();
        out.setOutput(stream, StandardCharsets.UTF_8.name());
        out.startDocument(/* encoding= */ null, /* standalone= */ true);
        out.startTag(/* namespace= */ null, LOCALES_XML_TAG);

        if (forStage) {
            out.attribute(/* namespace= */ null, ATTR_CREATION_TIME_MILLIS,
                    Long.toString(creationTimeMillis));
        }

        for (String pkg : pkgStates.keySet()) {
            out.startTag(/* namespace= */ null, PACKAGE_XML_TAG);
            out.attribute(/* namespace= */ null, ATTR_PACKAGE_NAME, pkg);
            out.attribute(/* namespace= */ null, ATTR_LOCALES, pkgStates.get(pkg));
            out.endTag(/*namespace= */ null, PACKAGE_XML_TAG);
        }

        out.endTag(/* namespace= */ null, LOCALES_XML_TAG);
        out.endDocument();
    }

    private static class StagedData {
        final long mCreationTimeMillis;
        final HashMap<String, String> mPackageStates;

        StagedData(long creationTimeMillis, HashMap<String, String> pkgStates) {
            mCreationTimeMillis = creationTimeMillis;
            mPackageStates = pkgStates;
        }
    }

    /**
     * Broadcast listener to capture user removed event.
     *
     * <p>The stage file is deleted when a user is removed.
     */
    private final class UserMonitor extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                if (action.equals(Intent.ACTION_USER_REMOVED)) {
                    final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, USER_NULL);
                    synchronized (mStagedDataLock) {
                        deleteStagedDataLocked(userId);
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Exception in user monitor.", e);
            }
        }
    }

    /**
     * Helper to monitor package states.
     *
     * <p>We're interested in package added, package data cleared and package removed events.
     */
    private final class PackageMonitorImpl extends PackageMonitor {
        @Override
        public void onPackageAdded(String packageName, int uid) {
            try {
                synchronized (mStagedDataLock) {
                    int userId = UserHandle.getUserId(uid);
                    if (mStagedData.contains(userId)) {
                        // Perform lazy restore only if the staged data exists.
                        doLazyRestoreLocked(packageName, userId);
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Exception in onPackageAdded.", e);
            }
        }

        @Override
        public void onPackageDataCleared(String packageName, int uid) {
            try {
                notifyBackupManager();
            } catch (Exception e) {
                Slog.e(TAG, "Exception in onPackageDataCleared.", e);
            }
        }

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            try {
                notifyBackupManager();
            } catch (Exception e) {
                Slog.e(TAG, "Exception in onPackageRemoved.", e);
            }
        }
    }

    /**
     * Performs lazy restore from the staged data.
     *
     * <p>This is invoked by the package monitor on the package added callback.
     */
    private void doLazyRestoreLocked(String packageName, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "doLazyRestore package=" + packageName + " user=" + userId);
        }

        // Check if the package is installed indeed
        if (!isPackageInstalledForUser(packageName, userId)) {
            Slog.e(TAG, packageName + " not installed for user " + userId
                    + ". Could not restore locales from stage file");
            return;
        }

        StagedData stagedData = mStagedData.get(userId);
        for (String pkgName : stagedData.mPackageStates.keySet()) {
            String languageTags = stagedData.mPackageStates.get(pkgName);

            if (pkgName.equals(packageName)) {

                checkExistingLocalesAndApplyRestore(pkgName, languageTags, userId);

                // Remove the restored entry from the staged data list.
                stagedData.mPackageStates.remove(pkgName);
                // Update the file on the disk.
                writeStageFileLocked(userId);

                // No need to loop further after restoring locales because the staged data will
                // contain at most one entry for the newly added package.
                break;
            }
        }
    }
}