package io.pure.yaml.binding.mapper;

import java.io.File;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * JDK value types that are written as strings and parsed back from them.
 */
final class ValueTypes {
    private static final List<Codec> CODECS = List.of(
        codec(UUID.class, UUID::fromString),
        codec(URI.class, URI::create),
        codec(Path.class, value -> Path.of(value)),
        codec(File.class, File::new),
        codec(Instant.class, Instant::parse),
        codec(LocalDate.class, LocalDate::parse),
        codec(LocalTime.class, LocalTime::parse),
        codec(LocalDateTime.class, LocalDateTime::parse),
        codec(OffsetDateTime.class, OffsetDateTime::parse),
        codec(OffsetTime.class, OffsetTime::parse),
        codec(ZonedDateTime.class, ZonedDateTime::parse),
        codec(Duration.class, Duration::parse),
        codec(Period.class, Period::parse),
        codec(Year.class, Year::parse),
        codec(YearMonth.class, YearMonth::parse),
        codec(MonthDay.class, MonthDay::parse),
        codec(ZoneOffset.class, ZoneOffset::of),
        codec(ZoneId.class, ZoneId::of),
        new Codec(Locale.class, Locale::forLanguageTag, value -> ((Locale) value).toLanguageTag()),
        codec(Currency.class, Currency::getInstance),
        new Codec(Charset.class, Charset::forName, value -> ((Charset) value).name())
    );

    private ValueTypes() {
    }

    static Codec forValue(Object value) {
        for (Codec codec : CODECS) {
            if (codec.type().isInstance(value)) {
                return codec;
            }
        }
        return null;
    }

    static Codec forType(Class<?> type) {
        for (Codec codec : CODECS) {
            if (codec.type() == type) {
                return codec;
            }
        }
        return null;
    }

    private static Codec codec(Class<?> type, Function<String, ?> parser) {
        return new Codec(type, parser, Object::toString);
    }

    record Codec(Class<?> type, Function<String, ?> parser, Function<Object, String> formatter) {
    }
}
