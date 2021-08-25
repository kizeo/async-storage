package com.reactnativecommunity.asyncstorage;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;

// A utility class that migrates a scoped AsyncStorage database to RKStorage.
// This utility only runs if the RKStorage file has not been created yet.
public class AsyncStorageExpoMigration {
    static final String LOG_TAG = "AsyncStorageExpoMigration";

    public static void migrate(Context context) {
        // Only migrate if the default async storage file does not exist.
        if (isAsyncStorageDatabaseCreated(context)) {
            return;
        }

        ArrayList<File> expoDatabases = getFilteredExpoDatabases(context);
        File expoDatabase = getLastModifiedFile(expoDatabases);

        if (expoDatabase == null) {
            Log.v(LOG_TAG, "No scoped database found");
            return;
        }

        try {
            // Create the storage file
            ReactDatabaseSupplier.getInstance(context).get();
            copyFile(new FileInputStream(expoDatabase), new FileOutputStream(context.getDatabasePath(ReactDatabaseSupplier.DATABASE_NAME)));
            Log.v(LOG_TAG, "Migrated most recently modified database " + expoDatabase.getName() + " to RKStorage");
        } catch (Exception e) {
            Log.v(LOG_TAG, "Failed to migrate scoped database " + expoDatabase.getName());
            e.printStackTrace();
            return;
        }

        // Migrate Databases Write-ahead logging (WAL)
        migrateWalDatabases(context, expoDatabase);

        // Delete old Expo databases after migration
        try {
            for (File file : getExpoDatabases(context)) {
                if (file.delete()) {
                    Log.v(LOG_TAG, "Deleted scoped database " + file.getName());
                } else {
                    Log.v(LOG_TAG, "Failed to delete scoped database " + file.getName());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        Log.v(LOG_TAG, "Completed the scoped AsyncStorage migration");
    }

    private static boolean isAsyncStorageDatabaseCreated(Context context) {
        return context.getDatabasePath(ReactDatabaseSupplier.DATABASE_NAME).exists();
    }

    // Find all database files that the user may have created while using Expo.
    private static ArrayList<File> getFilteredExpoDatabases(Context context) {
        ArrayList<File> scopedDatabases = new ArrayList<>();
        try {
            File[] directoryListing = getDirectoryListing(context);
            if (directoryListing != null) {
                for (File child : directoryListing) {
                    // Find all databases matching the Expo scoped key, and skip any database journals and database in Wal format.
                    if (child.getName().startsWith("RKStorage-scoped-experience-") && !(child.getName().endsWith("-journal")
                            || child.getName().endsWith("-shm") || child.getName().endsWith("-wal"))) {
                        scopedDatabases.add(child);
                    }
                }
            }
        } catch (Exception e) {
            // Just in case anything happens catch and print, file system rules can tend to be different across vendors.
            e.printStackTrace();
        }
        return scopedDatabases;
    }

    private static ArrayList<File> getExpoDatabases(Context context) {
        ArrayList<File> scopedDatabases = new ArrayList<>();
        try {
            File[] directoryListing = getDirectoryListing(context);
            if (directoryListing != null) {
                for (File child : directoryListing) {
                    // Find all databases matching the Expo scoped key, and skip any database journals.
                    if (child.getName().startsWith("RKStorage-scoped-experience-"))
                    {
                        scopedDatabases.add(child);
                    }
                }
            }
        } catch (Exception e) {
            // Just in case anything happens catch and print, file system rules can tend to be different across vendors.
            e.printStackTrace();
        }
        return scopedDatabases;
    }

    private static File[] getDirectoryListing(Context context){
        File databaseDirectory = context.getDatabasePath("noop").getParentFile();
        return databaseDirectory.listFiles();
    }

    private static void migrateWalDatabases(Context context, File expoDatabase) {

        // Check if the database with the same name and a suffix -wal & -shm (WAL Mode) and keep the databases
        replaceFile(context, expoDatabase, "-wal");
        replaceFile(context, expoDatabase, "-shm");
    }


    private static void replaceFile(Context context, File expoDatabase,  String suffix) {
        File pathFile = new File (expoDatabase.getPath()+suffix);
        if (pathFile.exists() && pathFile.isFile()){
            try {
                copyFile(new FileInputStream(pathFile), new FileOutputStream(context.getDatabasePath(ReactDatabaseSupplier.DATABASE_NAME + suffix)));
                Log.v(LOG_TAG, "ReactDatabaseSupplier.DATABASE_NAME =  " + ReactDatabaseSupplier.DATABASE_NAME);
                Log.v(LOG_TAG, "Migrated most recently modified database " + pathFile.getName() + " to RKStorage" + suffix);
            } catch (Exception e) {
                Log.v(LOG_TAG, "Failed to migrate scoped database " + pathFile.getName());
                e.printStackTrace();
                return;
            }
        }
    }

    // Returns the most recently modified file.
    // If a user publishes an app with Expo, then changes the slug
    // and publishes again, a new database will be created.
    // We want to select the most recent database and migrate it to RKStorage.
    private static File getLastModifiedFile(ArrayList<File> files) {
        if (files.size() == 0) {
            return null;
        }
        long lastMod = -1;
        File lastModFile = null;
        for (File child : files) {
            long modTime = getLastModifiedTimeInMillis(child);
            if (modTime > lastMod) {
                lastMod = modTime;
                lastModFile = child;
            }
        }
        if (lastModFile != null) {
            return lastModFile;
        }

        return files.get(0);
    }

    private static long getLastModifiedTimeInMillis(File file) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return getLastModifiedTimeFromBasicFileAttrs(file);
            } else {
                return file.lastModified();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private static long getLastModifiedTimeFromBasicFileAttrs(File file) {
        try {
            return Files.readAttributes(file.toPath(), BasicFileAttributes.class).creationTime().toMillis();
        } catch (Exception e) {
            return -1;
        }
    }

    private static void copyFile(FileInputStream fromFile, FileOutputStream toFile) throws IOException {
        FileChannel fromChannel = null;
        FileChannel toChannel = null;
        try {
            fromChannel = fromFile.getChannel();
            toChannel = toFile.getChannel();
            fromChannel.transferTo(0, fromChannel.size(), toChannel);
        } finally {
            try {
                if (fromChannel != null) {
                    fromChannel.close();
                }
            } finally {
                if (toChannel != null) {
                    toChannel.close();
                }
            }
        }
    }
}
