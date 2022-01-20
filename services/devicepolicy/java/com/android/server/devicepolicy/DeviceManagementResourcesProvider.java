/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.devicepolicy;

import static android.app.admin.DevicePolicyResources.Drawable.Source.UPDATABLE_DRAWABLE_SOURCES;
import static android.app.admin.DevicePolicyResources.Drawable.Style;
import static android.app.admin.DevicePolicyResources.Drawable.Style.UPDATABLE_DRAWABLE_STYLES;
import static android.app.admin.DevicePolicyResources.Drawable.UPDATABLE_DRAWABLE_IDS;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DevicePolicyDrawableResource;
import android.app.admin.DevicePolicyResources;
import android.app.admin.ParcelableResource;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Log;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A helper class for {@link DevicePolicyManagerService} to store/retrieve updated device
 * management resources.
 */
class DeviceManagementResourcesProvider {
    private static final String TAG = "DevicePolicyManagerService";

    private static final String UPDATED_RESOURCES_XML = "updated_resources.xml";
    private static final String TAG_ROOT = "root";
    private static final String TAG_DRAWABLE_STYLE_ENTRY = "drawable-style-entry";
    private static final String TAG_DRAWABLE_SOURCE_ENTRY = "drawable-source-entry";
    private static final String ATTR_DRAWABLE_STYLE_SIZE = "drawable-style-size";
    private static final String ATTR_DRAWABLE_SOURCE_SIZE = "drawable-source-size";
    private static final String ATTR_DRAWABLE_STYLE = "drawable-style";
    private static final String ATTR_DRAWABLE_SOURCE = "drawable-source";
    private static final String ATTR_DRAWABLE_ID = "drawable-id";


    private final Map<Integer, Map<Integer, ParcelableResource>>
            mUpdatedDrawablesForStyle = new HashMap<>();

    private final Map<Integer, Map<Integer, ParcelableResource>>
            mUpdatedDrawablesForSource = new HashMap<>();

    private final Object mLock = new Object();
    private final Injector mInjector;

    DeviceManagementResourcesProvider() {
        this(new Injector());
    }

    DeviceManagementResourcesProvider(Injector injector) {
        mInjector = requireNonNull(injector);
    }

    /**
     * Returns {@code false} if no resources were updated.
     */
    boolean updateDrawables(@NonNull List<DevicePolicyDrawableResource> drawables) {
        boolean updated = false;
        for (int i = 0; i < drawables.size(); i++) {
            int drawableId = drawables.get(i).getDrawableId();
            int drawableStyle = drawables.get(i).getDrawableStyle();
            int drawableSource = drawables.get(i).getDrawableSource();
            ParcelableResource resource = drawables.get(i).getResource();

            Objects.requireNonNull(resource, "ParcelableResource must be provided.");

            if (drawableSource == DevicePolicyResources.Drawable.Source.UNDEFINED) {
                updated |= updateDrawable(drawableId, drawableStyle, resource);
            } else {
                updated |= updateDrawableForSource(drawableId, drawableSource, resource);
            }
        }
        if (!updated) {
            return false;
        }
        synchronized (mLock) {
            write();
            return true;
        }
    }

    private boolean updateDrawable(
            int drawableId, int drawableStyle, ParcelableResource updatableResource) {
        if (!UPDATABLE_DRAWABLE_IDS.contains(drawableId)) {
            throw new IllegalArgumentException(
                    "Can't update drawable resource, invalid drawable " + "id " + drawableId);
        }
        if (!UPDATABLE_DRAWABLE_STYLES.contains(drawableStyle)) {
            throw new IllegalArgumentException(
                    "Can't update drawable resource, invalid style id " + drawableStyle);
        }
        synchronized (mLock) {
            if (!mUpdatedDrawablesForStyle.containsKey(drawableId)) {
                mUpdatedDrawablesForStyle.put(drawableId, new HashMap<>());
            }
            ParcelableResource current = mUpdatedDrawablesForStyle.get(drawableId).get(
                    drawableStyle);
            if (updatableResource.equals(current)) {
                return false;
            }
            mUpdatedDrawablesForStyle.get(drawableId).put(drawableStyle, updatableResource);
            return true;
        }
    }

    // TODO(b/214576716): change this to respect style
    private boolean updateDrawableForSource(
            int drawableId, int drawableSource, ParcelableResource updatableResource) {
        if (!UPDATABLE_DRAWABLE_IDS.contains(drawableId)) {
            throw new IllegalArgumentException("Can't update drawable resource, invalid drawable "
                    + "id " + drawableId);
        }
        if (!UPDATABLE_DRAWABLE_SOURCES.contains(drawableSource)) {
            throw new IllegalArgumentException("Can't update drawable resource, invalid source id "
                    + drawableSource);
        }
        synchronized (mLock) {
            if (!mUpdatedDrawablesForSource.containsKey(drawableId)) {
                mUpdatedDrawablesForSource.put(drawableId, new HashMap<>());
            }
            ParcelableResource current = mUpdatedDrawablesForSource.get(drawableId).get(
                    drawableSource);
            if (updatableResource.equals(current)) {
                return false;
            }
            mUpdatedDrawablesForSource.get(drawableId).put(drawableSource, updatableResource);
            return true;
        }
    }

    /**
     * Returns {@code false} if no resources were removed.
     */
    boolean removeDrawables(@NonNull int[] drawableIds) {
        synchronized (mLock) {
            boolean removed = false;
            for (int i = 0; i < drawableIds.length; i++) {
                int drawableId = drawableIds[i];
                removed |= mUpdatedDrawablesForStyle.remove(drawableId) != null
                        || mUpdatedDrawablesForSource.remove(drawableId) != null;
            }
            if (!removed) {
                return false;
            }
            write();
            return true;
        }
    }

    @Nullable
    ParcelableResource getDrawable(
            int drawableId, int drawableStyle, int drawableSource) {
        if (!UPDATABLE_DRAWABLE_IDS.contains(drawableId)) {
            Log.e(TAG, "Can't get updated drawable resource, invalid drawable id "
                    + drawableId);
            return null;
        }
        if (!UPDATABLE_DRAWABLE_STYLES.contains(drawableStyle)) {
            Log.e(TAG, "Can't get updated drawable resource, invalid style id "
                    + drawableStyle);
            return null;
        }
        if (!UPDATABLE_DRAWABLE_SOURCES.contains(drawableSource)) {
            Log.e(TAG, "Can't get updated drawable resource, invalid source id "
                    + drawableSource);
            return null;
        }
        if (mUpdatedDrawablesForSource.containsKey(drawableId)
                && mUpdatedDrawablesForSource.get(drawableId).containsKey(drawableSource)) {
            return mUpdatedDrawablesForSource.get(drawableId).get(drawableSource);
        }
        if (!mUpdatedDrawablesForStyle.containsKey(drawableId)) {
            Log.d(TAG, "No updated drawable found for drawable id " + drawableId);
            return null;
        }
        if (mUpdatedDrawablesForStyle.get(drawableId).containsKey(drawableStyle)) {
            return mUpdatedDrawablesForStyle.get(drawableId).get(drawableStyle);
        }

        if (mUpdatedDrawablesForStyle.get(drawableId).containsKey(Style.DEFAULT)) {
            return mUpdatedDrawablesForStyle.get(drawableId).get(Style.DEFAULT);
        }
        Log.d(TAG, "No updated drawable found for drawable id " + drawableId);
        return null;
    }

    private void write() {
        Log.d(TAG, "Writing updated resources to file.");
        new ResourcesReaderWriter().writeToFileLocked();
    }

    void load() {
        synchronized (mLock) {
            new ResourcesReaderWriter().readFromFileLocked();
        }
    }

    private File getResourcesFile() {
        return new File(mInjector.environmentGetDataSystemDirectory(), UPDATED_RESOURCES_XML);
    }

    private class ResourcesReaderWriter {
        private final File mFile;
        private ResourcesReaderWriter() {
            mFile = getResourcesFile();
        }

        void writeToFileLocked() {
            Log.d(TAG, "Writing to " + mFile);

            AtomicFile f = new AtomicFile(mFile);
            FileOutputStream outputStream = null;
            try {
                outputStream = f.startWrite();
                TypedXmlSerializer out = Xml.resolveSerializer(outputStream);

                // Root tag
                out.startDocument(null, true);
                out.startTag(null, TAG_ROOT);

                // Actual content
                writeInner(out);

                // Close root
                out.endTag(null, TAG_ROOT);
                out.endDocument();
                out.flush();

                // Commit the content.
                f.finishWrite(outputStream);
                outputStream = null;

            } catch (IOException e) {
                Log.e(TAG, "Exception when writing", e);
                if (outputStream != null) {
                    f.failWrite(outputStream);
                }
            }
        }

        void readFromFileLocked() {
            if (!mFile.exists()) {
                Log.d(TAG, "" + mFile + " doesn't exist");
                return;
            }

            Log.d(TAG, "Reading from " + mFile);
            AtomicFile f = new AtomicFile(mFile);
            InputStream input = null;
            try {
                input = f.openRead();
                TypedXmlPullParser parser = Xml.resolvePullParser(input);

                int type;
                int depth = 0;
                while ((type = parser.next()) != TypedXmlPullParser.END_DOCUMENT) {
                    switch (type) {
                        case TypedXmlPullParser.START_TAG:
                            depth++;
                            break;
                        case TypedXmlPullParser.END_TAG:
                            depth--;
                            // fallthrough
                        default:
                            continue;
                    }
                    // Check the root tag
                    String tag = parser.getName();
                    if (depth == 1) {
                        if (!TAG_ROOT.equals(tag)) {
                            Log.e(TAG, "Invalid root tag: " + tag);
                            return;
                        }
                        continue;
                    }
                    // readInner() will only see START_TAG at depth >= 2.
                    if (!readInner(parser, depth, tag)) {
                        return; // Error
                    }
                }
            } catch (XmlPullParserException | IOException e) {
                Log.e(TAG, "Error parsing resources file", e);
            } finally {
                IoUtils.closeQuietly(input);
            }
        }

        void writeInner(TypedXmlSerializer out) throws IOException {
            if (mUpdatedDrawablesForStyle != null && !mUpdatedDrawablesForStyle.isEmpty()) {
                for (Map.Entry<Integer, Map<Integer, ParcelableResource>> drawableEntry
                        : mUpdatedDrawablesForStyle.entrySet()) {
                    out.startTag(/* namespace= */ null, TAG_DRAWABLE_STYLE_ENTRY);
                    out.attributeInt(
                            /* namespace= */ null, ATTR_DRAWABLE_ID, drawableEntry.getKey());
                    out.attributeInt(
                            /* namespace= */ null,
                            ATTR_DRAWABLE_STYLE_SIZE,
                            drawableEntry.getValue().size());
                    int counter = 0;
                    for (Map.Entry<Integer, ParcelableResource> styleEntry
                            : drawableEntry.getValue().entrySet()) {
                        out.attributeInt(
                                /* namespace= */ null,
                                ATTR_DRAWABLE_STYLE + (counter++),
                                styleEntry.getKey());
                        styleEntry.getValue().writeToXmlFile(out);
                    }
                    out.endTag(/* namespace= */ null, TAG_DRAWABLE_STYLE_ENTRY);
                }
            }
            if (mUpdatedDrawablesForSource != null && !mUpdatedDrawablesForSource.isEmpty()) {
                for (Map.Entry<Integer, Map<Integer, ParcelableResource>> drawableEntry
                        : mUpdatedDrawablesForSource.entrySet()) {
                    out.startTag(/* namespace= */ null, TAG_DRAWABLE_SOURCE_ENTRY);
                    out.attributeInt(
                            /* namespace= */ null, ATTR_DRAWABLE_ID, drawableEntry.getKey());
                    out.attributeInt(
                            /* namespace= */ null,
                            ATTR_DRAWABLE_SOURCE_SIZE,
                            drawableEntry.getValue().size());
                    int counter = 0;
                    for (Map.Entry<Integer, ParcelableResource> sourceEntry
                            : drawableEntry.getValue().entrySet()) {
                        out.attributeInt(
                                /* namespace= */ null,
                                ATTR_DRAWABLE_SOURCE + (counter++),
                                sourceEntry.getKey());
                        sourceEntry.getValue().writeToXmlFile(out);
                    }
                    out.endTag(/* namespace= */ null, TAG_DRAWABLE_SOURCE_ENTRY);
                }
            }
        }

        private boolean readInner(
                TypedXmlPullParser parser, int depth, String tag)
                throws XmlPullParserException, IOException {
            if (depth > 2) {
                return true; // Ignore
            }
            switch (tag) {
                case TAG_DRAWABLE_STYLE_ENTRY:
                    int drawableId = parser.getAttributeInt(
                            /* namespace= */ null, ATTR_DRAWABLE_ID);
                    mUpdatedDrawablesForStyle.put(
                            drawableId,
                            new HashMap<>());
                    int size = parser.getAttributeInt(
                            /* namespace= */ null, ATTR_DRAWABLE_STYLE_SIZE);
                    for (int i = 0; i < size; i++) {
                        int style = parser.getAttributeInt(
                                /* namespace= */ null, ATTR_DRAWABLE_STYLE + i);
                        mUpdatedDrawablesForStyle.get(drawableId).put(
                                style,
                                ParcelableResource.createFromXml(parser));
                    }
                    break;
                case TAG_DRAWABLE_SOURCE_ENTRY:
                    drawableId = parser.getAttributeInt(
                            /* namespace= */ null, ATTR_DRAWABLE_ID);
                    mUpdatedDrawablesForSource.put(drawableId, new HashMap<>());
                    size = parser.getAttributeInt(
                            /* namespace= */ null, ATTR_DRAWABLE_SOURCE_SIZE);
                    for (int i = 0; i < size; i++) {
                        int source = parser.getAttributeInt(
                                /* namespace= */ null, ATTR_DRAWABLE_SOURCE + i);
                        mUpdatedDrawablesForSource.get(drawableId).put(
                                source,
                                ParcelableResource.createFromXml(parser));
                    }
                    break;
                default:
                    Log.e(TAG, "Unexpected tag: " + tag);
                    return false;
            }
            return true;
        }
    }

    public static class Injector {
        File environmentGetDataSystemDirectory() {
            return Environment.getDataSystemDirectory();
        }
    }
}