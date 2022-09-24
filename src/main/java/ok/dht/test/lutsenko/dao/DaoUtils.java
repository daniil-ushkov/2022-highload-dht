package ok.dht.test.lutsenko.dao;

import ok.dht.test.lutsenko.dao.common.BaseEntry;
import one.nio.util.Utf8;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.Iterator;

import static ok.dht.test.lutsenko.dao.DaoUtils2.postprocess;
import static ok.dht.test.lutsenko.dao.DaoUtils2.preprocess;

public final class DaoUtils {

    public static final int WRITE_BUFFER_SIZE = 16384;
    public static final int NULL_BYTES = 8;
    public static final int BYTES_IN_INT = Integer.SIZE / Byte.SIZE;
    public static final int DELETED_MARK = 0;
    public static final int EXISTING_MARK = 1;
    public static final byte NEXT_LINE_BYTE = (byte) '\n';

    private DaoUtils() {
    }

    public static int bytesOf(BaseEntry<String> entry) {
        return entry.key().length() + (entry.value() == null ? NULL_BYTES : entry.value().length());
    }

    public static String readKey(ByteBuffer byteBuffer) {
        int keyLength = byteBuffer.getInt();
        byte[] keyBytes = new byte[keyLength];
        byteBuffer.get(keyBytes);
        return postprocess(keyBytes);
    }

    public static String readValue(ByteBuffer byteBuffer) {
        if (byteBuffer.getInt() == EXISTING_MARK) {
            int valueLength = byteBuffer.getInt();
            byte[] valueBytes = new byte[valueLength];
            byteBuffer.get(valueBytes);
            byteBuffer.get(); // читаем '\n'
            return postprocess(valueBytes);
        }
        byteBuffer.get(); // читаем '\n'
        return null;
    }

    public static BaseEntry<String> readEntry(ByteBuffer byteBuffer) {
        byteBuffer.position(byteBuffer.position() + BYTES_IN_INT); // Пропускаем длину предыдущей записи
        if (isEnd(byteBuffer)) {
            return null;
        }
        return new BaseEntry<>(readKey(byteBuffer), readValue(byteBuffer));
    }

    public static void writeKey(byte[] keyBytes, ByteBuffer byteBuffer) {
        byteBuffer.putInt(keyBytes.length);
        byteBuffer.put(keyBytes);
    }

    public static void writeValue(byte[] valueBytes, ByteBuffer byteBuffer) {
        if (valueBytes == null) {
            byteBuffer.putInt(DELETED_MARK);
            byteBuffer.put(NEXT_LINE_BYTE);
        } else {
            byteBuffer.putInt(EXISTING_MARK);
            byteBuffer.putInt(valueBytes.length);
            byteBuffer.put(valueBytes);
            byteBuffer.put(NEXT_LINE_BYTE);
        }
    }

    public static void writeToFile(Path dataFilePath, Iterator<BaseEntry<String>> iterator) throws IOException {
        try (
                FileChannel channel = (FileChannel) Files.newByteChannel(dataFilePath,
                        EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW))
        ) {
            ByteBuffer writeBuffer = ByteBuffer.allocate(WRITE_BUFFER_SIZE);
            writeBuffer.putInt(0);
            while (iterator.hasNext()) {
                BaseEntry<String> baseEntry = iterator.next();
                byte[] keyBytes = Utf8.toBytes(preprocess(baseEntry.key()));
                byte[] valueBytes = (baseEntry.value() == null ? null : Utf8.toBytes(preprocess(baseEntry.value())));
                int entrySize = BYTES_IN_INT // размер численного значения для длины ключа
                        + keyBytes.length
                        + BYTES_IN_INT // размер численного значения для длины значения
                        + (valueBytes == null ? 0 : valueBytes.length)
                        + BYTES_IN_INT // размер всей записи
                        + BYTES_IN_INT // DELETED_MARK or EXISTING_MARK
                        + 1; // размер '\n'
                if (writeBuffer.position() + entrySize > writeBuffer.capacity()) {
                    writeBuffer.flip();
                    channel.write(writeBuffer);
                    writeBuffer.clear();
                }
                if (entrySize > writeBuffer.capacity()) {
                    writeBuffer = ByteBuffer.allocate(entrySize);
                }
                writeKey(keyBytes, writeBuffer);
                writeValue(valueBytes, writeBuffer);
                writeBuffer.putInt(entrySize);
            }
            writeBuffer.flip();
            channel.write(writeBuffer);
            writeBuffer.clear();
        }
    }

    /**
     * Для лучшего понимаю См. Описание формата файла в PersistenceRangeDao.
     * Все размер / длины ы в количественном измерении относительно char, то есть int это 2 char
     * Везде, где упоминается размер / длина, имеется в виду относительно char, а не байтов.
     * left - левая граница,
     * right - правая граница равная размеру файла минус размер числа,
     * которое означает длину относящегося предыдущей записи
     * Минусуем, чтобы гарантированно читать это число целиком.
     * position - середина по размеру между left и right, (left + right) / 2;
     * position после операции выше указывает на ту позицию относительно начала строки, на какую повезет,
     * необязательно на начало. При этом ситуации когда идет "многократное попадание в одну и ту же entry не существует"
     * Поэтому реализован гарантированный переход на начало следующей строки, для этого делается readline,
     * Каждое entry начинается с новой строки ('\n' в исходном ключе и значении экранируется)
     * Начало строки начинается всегда с размера прошлой строки, то есть прошлой entry
     * плюс размера одного int(этого же число, но на прошлой строке)
     * При этом left всегда указывает на начало строки, а right на конец (речь про разные строки / entry)
     * Перед тем как переходить на position в середину, всегда ставиться метка в позиции left, то есть в начале строки
     * Всегда идет проверка на случай если мы пополи на середину последней строки :
     * position + readBytes(прочитанные байты с помощью readline) == right,
     * Если равенство выполняется, то возвращаемся в конец последней строки -
     * position ставим в left + CHARS_IN_INT (длина числа, размера предыдущей строки), readBytes обнуляем,
     * дальше идет обычная обработка :
     * Читаем ключ, значение (все равно придется его читать чтобы дойти до след ключа),
     * сравниваем ключ, если он равен, то return
     * Если текущий ключ меньше заданного, то читаем следующий
     * Если следующего нет, то return null, так ищем границу сверху, а последний ключ меньше заданного
     * Если этот следующий ключ меньше или равен заданному, то читаем его value и return
     * В зависимости от результата сравнения left и right устанавливаем в начало или конец рассматриваемого ключа
     * mark делается всегда в начале entry то есть в позиции left. В первом случае чтобы вернуться, как бы skip наоборот
     * Во втором чтобы сделать peek для nextKey и была возможность просмотреть его и вернуться в его начало.
     * В итоге идея следующая найти пару ключей, между которыми лежит исходный и вернуть второй или равный исходному,
     * при этом не храня индексы для сдвигов ключей вовсе.
     */
    public static BaseEntry<String> ceilKey(ByteBuffer byteBuffer, String key) {
        int prevEntryLength;
        String currentKey;
        long left = 0;
        long right = (long) byteBuffer.capacity() - BYTES_IN_INT;
        long position;
        while (left < right) {
            position = (left + right) / 2;
            byteBuffer.mark();
            byteBuffer.position((int) position);
            int leastPartOfLineLength = getLeastPartOfLineLength(byteBuffer);
            int readBytes = leastPartOfLineLength + BYTES_IN_INT; // BYTES_IN_INT -> prevEntryLength
            prevEntryLength = byteBuffer.getInt();
            if (position + readBytes >= right) {
                byteBuffer.reset();
                byteBuffer.position(byteBuffer.position() + BYTES_IN_INT);
                right = position + readBytes - prevEntryLength + 1;
                readBytes = 0;
                position = left;
            }
            currentKey = readKey(byteBuffer);
            String currentValue = readValue(byteBuffer);
            int compareResult = key.compareTo(currentKey);
            if (compareResult == 0) {
                return new BaseEntry<>(currentKey, currentValue);
            }
            if (compareResult > 0) {
                byteBuffer.mark();
                prevEntryLength = byteBuffer.getInt();
                if (isEnd(byteBuffer)) {
                    return null;
                }
                String nextKey = readKey(byteBuffer);
                if (key.compareTo(nextKey) <= 0) {
                    return new BaseEntry<>(nextKey, readValue(byteBuffer));
                }
                left = position + readBytes + prevEntryLength;
                byteBuffer.reset();
            } else {
                right = position + readBytes;
                byteBuffer.reset();
            }
        }
        return left == 0 ? readEntry(byteBuffer) : null;
    }

    private static int getLeastPartOfLineLength(ByteBuffer byteBuffer) {
        int leastPartOfLineLength = 0;
        while (byteBuffer.get() != NEXT_LINE_BYTE) {
            leastPartOfLineLength++;
        }
        return leastPartOfLineLength;
    }

    public static boolean isEnd(ByteBuffer byteBuffer) {
        return byteBuffer.position() == byteBuffer.limit();
    }
}
