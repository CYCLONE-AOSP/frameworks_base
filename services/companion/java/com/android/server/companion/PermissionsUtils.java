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

package com.android.server.companion;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.MANAGE_COMPANION_DEVICES;
import static android.Manifest.permission.REQUEST_COMPANION_SELF_MANAGED;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.companion.AssociationRequest.DEVICE_PROFILE_APP_STREAMING;
import static android.companion.AssociationRequest.DEVICE_PROFILE_AUTOMOTIVE_PROJECTION;
import static android.companion.AssociationRequest.DEVICE_PROFILE_WATCH;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Binder.getCallingUid;
import static android.os.Process.SYSTEM_UID;
import static android.os.UserHandle.getCallingUserId;

import static java.util.Collections.unmodifiableMap;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;

import com.android.internal.app.IAppOpsService;

import java.util.Map;

/**
 * Utility methods for checking permissions required for accessing {@link CompanionDeviceManager}
 * APIs (such as {@link Manifest.permission#REQUEST_COMPANION_PROFILE_WATCH},
 * {@link Manifest.permission#REQUEST_COMPANION_PROFILE_APP_STREAMING},
 * {@link Manifest.permission#REQUEST_COMPANION_SELF_MANAGED} etc.)
 */
final class PermissionsUtils {

    private static final Map<String, String> DEVICE_PROFILE_TO_PERMISSION;
    static {
        final Map<String, String> map = new ArrayMap<>();
        map.put(DEVICE_PROFILE_WATCH, Manifest.permission.REQUEST_COMPANION_PROFILE_WATCH);
        map.put(DEVICE_PROFILE_APP_STREAMING,
                Manifest.permission.REQUEST_COMPANION_PROFILE_APP_STREAMING);
        map.put(DEVICE_PROFILE_AUTOMOTIVE_PROJECTION,
                Manifest.permission.REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION);

        DEVICE_PROFILE_TO_PERMISSION = unmodifiableMap(map);
    }

    static void enforceRequestDeviceProfilePermissions(
            @NonNull Context context, @Nullable String deviceProfile) {
        // Device profile can be null.
        if (deviceProfile == null) return;

        if (!DEVICE_PROFILE_TO_PERMISSION.containsKey(deviceProfile)) {
            throw new IllegalArgumentException("Unsupported device profile: " + deviceProfile);
        }

        if (DEVICE_PROFILE_APP_STREAMING.equals(deviceProfile)) {
            // TODO: remove, when properly supporting this profile.
            throw new UnsupportedOperationException(
                    "DEVICE_PROFILE_APP_STREAMING is not fully supported yet.");
        }

        if (DEVICE_PROFILE_AUTOMOTIVE_PROJECTION.equals(deviceProfile)) {
            // TODO: remove, when properly supporting this profile.
            throw new UnsupportedOperationException(
                    "DEVICE_PROFILE_AUTOMOTIVE_PROJECTION is not fully supported yet.");
        }

        final String permission = DEVICE_PROFILE_TO_PERMISSION.get(deviceProfile);
        if (context.checkCallingOrSelfPermission(permission) != PERMISSION_GRANTED) {
            throw new SecurityException("Application must hold " + permission + " to associate "
                    + "with a device with " + deviceProfile + " profile.");
        }
    }

    static void enforceRequestSelfManagedPermission(@NonNull Context context) {
        if (context.checkCallingOrSelfPermission(REQUEST_COMPANION_SELF_MANAGED)
                != PERMISSION_GRANTED) {
            throw new SecurityException("Application does not hold "
                    + REQUEST_COMPANION_SELF_MANAGED);
        }
    }

    static boolean checkCallerCanInteractWithUserId(@NonNull Context context, int userId) {
        if (getCallingUserId() == userId) return true;

        return context.checkCallingPermission(INTERACT_ACROSS_USERS) == PERMISSION_GRANTED;
    }

    static void enforceCallerCanInteractWithUserId(@NonNull Context context, int userId) {
        if (getCallingUserId() == userId) return;

        context.enforceCallingPermission(INTERACT_ACROSS_USERS, null);
    }

    static boolean checkCallerIsSystemOr(@UserIdInt int userId, @NonNull String packageName) {
        final int callingUid = getCallingUid();
        if (callingUid == SYSTEM_UID) return true;

        if (getCallingUserId() != userId) return false;

        if (!checkPackage(callingUid, packageName)) return false;

        return true;
    }

    static void enforceCallerIsSystemOr(@UserIdInt int userId, @NonNull String packageName) {
        final int callingUid = getCallingUid();
        if (callingUid == SYSTEM_UID) return;

        final int callingUserId = getCallingUserId();
        if (getCallingUserId() != userId) {
            throw new SecurityException("Calling UserId (" + callingUserId + ") does not match "
                    + "the expected UserId (" + userId + ")");
        }

        if (!checkPackage(callingUid, packageName)) {
            throw new SecurityException(packageName + " doesn't belong to calling uid ("
                    + callingUid + ")");
        }
    }

    static boolean checkCallerCanManagerCompanionDevice(@NonNull Context context) {
        if (getCallingUserId() == SYSTEM_UID) return true;

        return context.checkCallingPermission(MANAGE_COMPANION_DEVICES) == PERMISSION_GRANTED;
    }

    static void enforceCallerCanManagerCompanionDevice(@NonNull Context context,
            @Nullable String message) {
        if (getCallingUserId() == SYSTEM_UID) return;

        context.enforceCallingPermission(MANAGE_COMPANION_DEVICES, message);
    }

    static boolean checkCallerCanManageAssociationsForPackage(@NonNull Context context,
            @UserIdInt int userId, @NonNull String packageName) {
        if (checkCallerIsSystemOr(userId, packageName)) return true;

        if (!checkCallerCanInteractWithUserId(context, userId)) return false;

        return checkCallerCanManagerCompanionDevice(context);
    }

    private static boolean checkPackage(@UserIdInt int uid, @NonNull String packageName) {
        try {
            return getAppOpsService().checkPackage(uid, packageName) == MODE_ALLOWED;
        } catch (RemoteException e) {
            // Can't happen: AppOpsManager is running in the same process.
            return true;
        }
    }

    private static IAppOpsService getAppOpsService() {
        if (sAppOpsService == null) {
            synchronized (PermissionsUtils.class) {
                if (sAppOpsService == null) {
                    sAppOpsService = IAppOpsService.Stub.asInterface(
                            ServiceManager.getService(Context.APP_OPS_SERVICE));
                }
            }
        }
        return sAppOpsService;
    }

    // DO NOT USE DIRECTLY! Access via getAppOpsService().
    private static IAppOpsService sAppOpsService = null;

    private PermissionsUtils() {}
}