// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

final class Logging {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS zzz").withZone(ZoneId.systemDefault());
    private static final long PID = ProcessHandle.current().pid();

    private Logging() {
    }

    static void configure(ServerConfig config) {
        configure(level(config.string(Setting.LOG_LEVEL)), config.string(Setting.LOG_DIRECTORY),
                config.integer(Setting.LOG_ROTATION_MB), config.integer(Setting.LOG_FILE_COUNT));
    }

    static void configure(Level level, String directory, int rotationMegabytes, int files) {
        LogManager.getLogManager().reset();
        Logger root = Logger.getLogger("");
        root.setLevel(level);
        Formatter formatter = new PostgresStyle();
        Handler console = new ConsoleHandler();
        console.setFormatter(formatter);
        console.setLevel(level);
        root.addHandler(console);
        if (!directory.isBlank()) {
            try {
                Files.createDirectories(Path.of(directory));
                FileHandler file = new FileHandler(Path.of(directory, "hstore-%g.log").toString(), rotationMegabytes << 20, files, true);
                file.setFormatter(formatter);
                file.setLevel(level);
                root.addHandler(file);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot open log directory " + directory, e);
            }
        }
    }

    static Level level(String name) {
        return switch (name.toLowerCase()) {
            case "debug" -> Level.FINE;
            case "info" -> Level.INFO;
            case "warning" -> Level.WARNING;
            case "error" -> Level.SEVERE;
            default -> throw new IllegalArgumentException("log_level must be debug, info, warning or error");
        };
    }

    private static final class PostgresStyle extends Formatter {
        @Override
        public String format(LogRecord record) {
            StringBuilder line = new StringBuilder(160)
                    .append(TIMESTAMP.format(Instant.ofEpochMilli(record.getMillis())))
                    .append(" [").append(PID).append("] ")
                    .append(severity(record.getLevel())).append(":  ")
                    .append(component(record.getLoggerName()))
                    .append(formatMessage(record))
                    .append(System.lineSeparator());
            if (record.getThrown() != null) {
                line.append("DETAIL:  ").append(record.getThrown()).append(System.lineSeparator());
                for (StackTraceElement frame : record.getThrown().getStackTrace()) {
                    line.append("\tat ").append(frame).append(System.lineSeparator());
                }
            }
            return line.toString();
        }

        private static String severity(Level level) {
            if (level.intValue() >= Level.SEVERE.intValue()) {
                return "ERROR";
            }
            if (level.intValue() >= Level.WARNING.intValue()) {
                return "WARNING";
            }
            if (level.intValue() >= Level.INFO.intValue()) {
                return "LOG";
            }
            return "DEBUG";
        }

        private static String component(String logger) {
            if (logger == null || logger.isEmpty()) {
                return "";
            }
            int dot = logger.lastIndexOf('.');
            return "[" + (dot < 0 ? logger : logger.substring(dot + 1)) + "] ";
        }
    }
}
