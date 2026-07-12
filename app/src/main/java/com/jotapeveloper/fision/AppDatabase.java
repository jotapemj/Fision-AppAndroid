package com.jotapeveloper.fision;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {db_settings.class, db_SongParameters.class}, version = 6)
public abstract class AppDatabase extends RoomDatabase {

    // Static field for the singleton database instance
    private static volatile AppDatabase INSTANCE;

    // Abstract methods to access the DAOs
    public abstract DAO_db_settings DAO_db_settings();
    public abstract DAO_db_SongParameters DAO_db_SongParameters();

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

    // Static method to obtain the database instance
    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "app_database")  // database name
                            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                            .fallbackToDestructiveMigration()  // fallback if explicit migration fails
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}

