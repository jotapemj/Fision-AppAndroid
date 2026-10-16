package com.jotapeveloper.fision;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {db_settings.class, db_SongParameters.class, db_Playlist.class}, version = 8)
public abstract class AppDatabase extends RoomDatabase {

    // Static field for the singleton database instance
    private static volatile AppDatabase INSTANCE;

    // Abstract methods to access the DAOs
    public abstract DAO_db_settings DAO_db_settings();
    public abstract DAO_db_SongParameters DAO_db_SongParameters();
    public abstract DAO_db_Playlist DAO_db_Playlist();

    /** v3 → v4: adds the `Chords basic mode` (boolean) column without losing data. */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE songParameters ADD COLUMN `Chords basic mode` INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v4 → v5: 0.5x metronome variant + downbeat sound (enable + type). */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE songParameters ADD COLUMN `Metronome half variant` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE songParameters ADD COLUMN `Downbeat sound enabled` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE songParameters ADD COLUMN `Downbeat sound type` INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v5 → v6: 3D audio (ITD/ILD) enabled per song. Per-stem positions live inside
     *  the stemSettingsJson (fields "x"/"y") and do not need a dedicated column. */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE songParameters ADD COLUMN `3D enabled` INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v6 → v7: adds `Tempo mult milli` (int, mult*1000). Rows with a legacy
     *  `Tempo index` are converted to their equivalent multiplier so users do not
     *  lose their per-song tempo when upgrading. Mapping mirrors the old fixed
     *  table numberPickerData.VALUES: mult = 0.24 + (idx - 1) * 0.01, which in
     *  thousandths becomes an exact integer (idx - 1) * 10 + 240. Rows with
     *  `Tempo index` = 0 (never touched) get the neutral 1000 default. */
    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE songParameters ADD COLUMN `Tempo mult milli` INTEGER NOT NULL DEFAULT 1000");
            database.execSQL("UPDATE songParameters SET `Tempo mult milli` = (`Tempo index` - 1) * 10 + 240 WHERE `Tempo index` > 0");
        }
    };

    /** v7 → v8: offline copy of the server playlists. New table only. */
    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `playlists` ("
                    + "`id` TEXT NOT NULL, `title` TEXT, `listIndex` INTEGER NOT NULL, "
                    + "`hasCover` INTEGER NOT NULL, `createdAt` TEXT, `updatedAt` TEXT, "
                    + "`songsJson` TEXT, PRIMARY KEY(`id`))");
        }
    };

    // Static method to obtain the database instance
    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "app_database")  // database name
                            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                            .fallbackToDestructiveMigration()  // fallback if explicit migration fails
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}

