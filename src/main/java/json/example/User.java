package json.example;

import java.util.ArrayList;
import java.util.List;

import json.JsonCodec;
import json.JsonField;
import json.JsonReader;
import json.JsonWriter;

/** Example of the codec pattern: one static nested class per type, no reflection. */
public final class User {

    public long id;
    public String name;
    public String email;
    public boolean active;
    public double score;
    public List<String> tags = new ArrayList<>();
    public Address address;

    public static final class Address {
        public String street;
        public String city;
        public int zip;

        public static final JsonCodec<Address> CODEC = new AddressCodec();
    }

    public static final JsonCodec<User> CODEC = new UserCodec();

    private static final class UserCodec implements JsonCodec<User> {
        // Field names are encoded once, at class-init time.
        private static final JsonField ID = JsonField.of("id");
        private static final JsonField NAME = JsonField.of("name");
        private static final JsonField EMAIL = JsonField.of("email");
        private static final JsonField ACTIVE = JsonField.of("active");
        private static final JsonField SCORE = JsonField.of("score");
        private static final JsonField TAGS = JsonField.of("tags");
        private static final JsonField ADDRESS = JsonField.of("address");

        @Override public void write(JsonWriter w, User u) {
            w.beginObject();
            w.name(ID).value(u.id);
            w.name(NAME).value(u.name);
            w.name(EMAIL).value(u.email);
            w.name(ACTIVE).value(u.active);
            w.name(SCORE).value(u.score);
            w.name(TAGS).beginArray();
            for (int i = 0, n = u.tags.size(); i < n; i++) w.value(u.tags.get(i));
            w.endArray();
            w.name(ADDRESS);
            if (u.address == null) w.nullValue(); else Address.CODEC.write(w, u.address);
            w.endObject();
        }

        @Override public User read(JsonReader r) {
            User u = new User();
            r.beginObject();
            while (r.nextKey()) {
                if (r.keyIs(ID)) u.id = r.readLong();
                else if (r.keyIs(NAME)) u.name = r.readString();
                else if (r.keyIs(EMAIL)) u.email = r.readString();
                else if (r.keyIs(ACTIVE)) u.active = r.readBoolean();
                else if (r.keyIs(SCORE)) u.score = r.readDouble();
                else if (r.keyIs(TAGS)) {
                    r.beginArray();
                    while (r.hasNextElement()) u.tags.add(r.readString());
                } else if (r.keyIs(ADDRESS)) {
                    u.address = r.isNull() ? null : Address.CODEC.read(r);
                } else {
                    r.skipValue(); // unknown fields cost a scan, never an allocation
                }
            }
            return u;
        }
    }

    private static final class AddressCodec implements JsonCodec<Address> {
        private static final JsonField STREET = JsonField.of("street");
        private static final JsonField CITY = JsonField.of("city");
        private static final JsonField ZIP = JsonField.of("zip");

        @Override public void write(JsonWriter w, Address a) {
            w.beginObject();
            w.name(STREET).value(a.street);
            w.name(CITY).value(a.city);
            w.name(ZIP).value(a.zip);
            w.endObject();
        }

        @Override public Address read(JsonReader r) {
            Address a = new Address();
            r.beginObject();
            while (r.nextKey()) {
                if (r.keyIs(STREET)) a.street = r.readString();
                else if (r.keyIs(CITY)) a.city = r.readString();
                else if (r.keyIs(ZIP)) a.zip = r.readInt();
                else r.skipValue();
            }
            return a;
        }
    }
}
