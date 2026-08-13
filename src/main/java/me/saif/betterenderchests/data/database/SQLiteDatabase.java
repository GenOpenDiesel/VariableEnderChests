package me.saif.betterenderchests.data.database;

import java.io.File;
import java.io.IOException;

public class SQLiteDatabase extends SQLDatabase {

    public static final String TYPE = "SQLite";

    private File folder;
    private String fileName;

    public SQLiteDatabase(File folder, String fileName) {
        this.folder = folder;
        this.fileName = fileName;

        if (!this.folder.isDirectory())
            this.folder.mkdirs();

        File file = new File(this.folder, this.fileName);
        try {
            if (!file.exists())
                file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        dataSource.setJdbcUrl("jdbc:sqlite:" + folder.toPath().toAbsolutePath().resolve(fileName));
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setConnectionTestQuery("SELECT 1");
        dataSource.setPoolName("[VariableEnderChests-SQLite]");

        //without these every single write is its own transaction with its own fsync, which makes
        //saving the chests of a full server take seconds while everything else waits in line for it.
        dataSource.addDataSourceProperty("journal_mode", "WAL");
        dataSource.addDataSourceProperty("synchronous", "NORMAL");
        //never fail instantly when another connection is writing, wait for it instead
        dataSource.addDataSourceProperty("busy_timeout", "10000");
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
