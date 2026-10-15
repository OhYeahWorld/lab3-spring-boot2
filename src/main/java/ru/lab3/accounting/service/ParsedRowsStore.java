package ru.lab3.accounting.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Кратковременное хранилище разобранных строк выписки между шагами импорта
 * («Разобрать файл» → «Сохранить правки» → «Импортировать в базу»).
 * Ключ — идентификатор сессии браузера, поэтому данные разных пользователей
 * и разных загрузок не смешиваются. Хранилище служит только подстраховкой:
 * если POST-форма пришла без строк (например, из-за лимита запросов сервера),
 * строки берутся отсюда, а не считаются потерянными.
 */
@Component
public class ParsedRowsStore {

    private static final long TTL_MILLIS = 60L * 60L * 1000L; // 1 час
    private static final int MAX_ENTRIES = 200;

    private record Entry(long timestamp, String fileName, List<StatementImportService.ImportRow> rows) {
    }

    private final ConcurrentMap<String, Entry> store = new ConcurrentHashMap<>();

    public void save(String key, String fileName, List<StatementImportService.ImportRow> rows) {
        if (key == null || rows == null) {
            return;
        }
        cleanup();
        if (store.size() >= MAX_ENTRIES && !store.containsKey(key)) {
            // защита от разрастания: при переполнении чистим всё просроченное и самое старое
            store.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue().timestamp() > TTL_MILLIS);
            if (store.size() >= MAX_ENTRIES) {
                store.entrySet().stream()
                        .min((a, b) -> Long.compare(a.getValue().timestamp(), b.getValue().timestamp()))
                        .ifPresent(e -> store.remove(e.getKey()));
            }
        }
        store.put(key, new Entry(System.currentTimeMillis(), fileName, List.copyOf(rows)));
    }

    /** Возвращает сохранённые строки для ключа или null, если их нет/они устарели. */
    public List<StatementImportService.ImportRow> get(String key) {
        if (key == null) {
            return null;
        }
        Entry entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.timestamp() > TTL_MILLIS) {
            store.remove(key);
            return null;
        }
        return entry.rows();
    }

    public String getFileName(String key) {
        Entry entry = key == null ? null : store.get(key);
        return entry == null ? "" : (entry.fileName() == null ? "" : entry.fileName());
    }

    public void remove(String key) {
        if (key != null) {
            store.remove(key);
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> now - e.getValue().timestamp() > TTL_MILLIS);
    }
}
