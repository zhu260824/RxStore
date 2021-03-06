/*
 * Copyright (C) GRIDSTONE 2016
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package au.com.gridstone.rxstore;

import au.com.gridstone.rxstore.StoreProvider.ValueStore;
import au.com.gridstone.rxstore.StoreProvider.ListStore;
import au.com.gridstone.rxstore.testutil.RecordingObserver;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import rx.Notification;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

import static com.google.common.truth.Truth.assertThat;

public final class StoreProviderTest {
  @Rule public TemporaryFolder tempDir = new TemporaryFolder();

  private StoreProvider storeProvider;

  @Before public void setup() throws IOException {
    storeProvider = StoreProvider.with(tempDir.newFolder("rxStoreTest"))
        .schedulingWith(Schedulers.immediate())
        .using(new TestConverter());
  }

  @Test public void putAndClear() {
    ValueStore<TestData> valueStore = storeProvider.valueStore("testValue", TestData.class);
    TestData value = new TestData("Test", 1);
    valueStore.put(value);
    assertThat(valueStore.getBlocking()).isEqualTo(value);

    valueStore.clear();
    assertThat(valueStore.getBlocking()).isNull();
  }

  @Test public void getOnEmptyReturnsNull() {
    ValueStore<TestData> valueStore = storeProvider.valueStore("testValue", TestData.class);
    assertThat(valueStore.getBlocking()).isNull();
  }

  @Test public void interactionsWithDeletedFail() {
    ValueStore<TestData> valueStore = storeProvider.valueStore("testValue", TestData.class);
    TestData value = new TestData("Test", 1);
    valueStore.put(value);
    valueStore.delete();

    String expectedMessage = "This store has been deleted!";

    Throwable getError = valueStore.get()
        .toObservable()
        .materialize()
        .filter(new Func1<Notification<TestData>, Boolean>() {
          @Override public Boolean call(Notification<TestData> notification) {
            return notification.isOnError();
          }
        })
        .map(new Func1<Notification<TestData>, Throwable>() {
          @Override public Throwable call(Notification<TestData> notification) {
            return notification.getThrowable();
          }
        })
        .toBlocking()
        .single();

    assertThat(getError).hasMessage(expectedMessage);

    Throwable putError = valueStore.observePut(new TestData("Test2", 2))
        .toObservable()
        .materialize()
        .filter(new Func1<Notification<TestData>, Boolean>() {
          @Override public Boolean call(Notification<TestData> notification) {
            return notification.isOnError();
          }
        })
        .map(new Func1<Notification<TestData>, Throwable>() {
          @Override public Throwable call(Notification<TestData> notification) {
            return notification.getThrowable();
          }
        })
        .toBlocking()
        .single();

    assertThat(putError).hasMessage(expectedMessage);

    Throwable clearError = valueStore.observeClear()
        .toObservable()
        .materialize()
        .filter(new Func1<Notification<TestData>, Boolean>() {
          @Override public Boolean call(Notification<TestData> notification) {
            return notification.isOnError();
          }
        })
        .map(new Func1<Notification<TestData>, Throwable>() {
          @Override public Throwable call(Notification<TestData> notification) {
            return notification.getThrowable();
          }
        })
        .toBlocking()
        .single();

    assertThat(clearError).hasMessage(expectedMessage);
  }

  @Test public void updatesTriggerObservable() {
    ValueStore<TestData> valueStore = storeProvider.valueStore("testValue", TestData.class);
    RecordingObserver<TestData> observer = new RecordingObserver<>();
    TestData value = new TestData("Test", 1);

    valueStore.asObservable().subscribe(observer);

    assertThat(observer.takeNext()).isNull();
    valueStore.put(value);
    assertThat(observer.takeNext()).isEqualTo(value);

    TestData value2 = new TestData("Test2", 2);
    valueStore.put(value2);
    assertThat(observer.takeNext()).isEqualTo(value2);

    valueStore.clear();
    assertThat(observer.takeNext()).isNull();

    observer.assertNoMoreEvents();
    valueStore.delete();
    assertThat(observer.takeNext()).isNull();
    observer.assertOnCompleted();
  }

  @Test public void putAndClearList() {
    ListStore<TestData> store = storeProvider.listStore("testValues", TestData.class);
    List<TestData> list = Arrays.asList(new TestData("Test1", 1), new TestData("Test2", 2));
    store.put(list);
    assertThat(store.getBlocking()).isEqualTo(list);

    store.clear();
    assertThat(store.getBlocking()).isEmpty();
  }

  @Test public void getOnEmptyListReturnsEmptyList() {
    ListStore<TestData> store = storeProvider.listStore("testValues", TestData.class);
    assertThat(store.getBlocking()).isEmpty();
  }

  @Test public void addToEmptyList() {
    ListStore<TestData> store = storeProvider.listStore("testValues", TestData.class);
    TestData value = new TestData("Test1", 1);
    store.addToList(value);
    assertThat(store.getBlocking()).containsExactly(value);
  }

  @Test public void addToExistingList() {
    ListStore<TestData> store = storeProvider.listStore("testValues", TestData.class);
    List<TestData> list = Arrays.asList(new TestData("Test1", 1), new TestData("Test2", 2));
    store.put(list);
    assertThat(store.getBlocking()).isEqualTo(list);

    TestData newValue = new TestData("TestAddition", 123);
    store.addToList(newValue);

    List<TestData> listPlusNewValue = new ArrayList<>(list);
    listPlusNewValue.add(newValue);

    assertThat(store.getBlocking()).containsExactlyElementsIn(listPlusNewValue);
  }

  @Test public void removeFromList() {
    ListStore<TestData> store = storeProvider.listStore("testValues", TestData.class);
    List<TestData> list = Arrays.asList(new TestData("Test1", 1), new TestData("Test2", 2));
    store.put(list);

    store.removeFromList(new TestData("Test1", 1));
    assertThat(store.getBlocking()).containsExactly(new TestData("Test2", 2));
  }

  @Test public void removeFromListByIndex() {
    ListStore<TestData> store = storeProvider.listStore("testValues", TestData.class);
    List<TestData> list = Arrays.asList(new TestData("Test1", 1), new TestData("Test2", 2));
    store.put(list);

    store.removeFromList(0);
    assertThat(store.getBlocking()).containsExactly(new TestData("Test2", 2));
  }

  @Test public void replaceInList() {
    ListStore<TestData> store = storeProvider.listStore("testValues", TestData.class);
    List<TestData> list = Arrays.asList(new TestData("Test1", 1), new TestData("Test2", 2));
    store.put(list);

    store.replace(new TestData("Test3", 3), new StoreProvider.ReplacePredicateFunc<TestData>() {
      @Override public boolean shouldReplace(TestData value) {
        return value.integer == 2;
      }
    });

    assertThat(store.getBlocking()).containsExactly(new TestData("Test1", 1),
        new TestData("Test3", 3));
  }

  @Test public void updateToListTriggerObservable() {
    ListStore<TestData> store = storeProvider.listStore("testValues", TestData.class);
    RecordingObserver<List<TestData>> observer = new RecordingObserver<>();
    List<TestData> list = Arrays.asList(new TestData("Test1", 1), new TestData("Test2", 2));

    store.asObservable().subscribe(observer);

    assertThat(observer.takeNext()).isEmpty();
    store.put(list);
    assertThat(observer.takeNext()).isEqualTo(list);

    TestData newValue = new TestData("Test3", 3);
    store.addToList(newValue);
    List<TestData> expectedList = new ArrayList<>(list);
    expectedList.add(newValue);
    assertThat(observer.takeNext()).isEqualTo(expectedList);

    store.clear();
    assertThat(observer.takeNext()).isEmpty();

    observer.assertNoMoreEvents();
    store.delete();
    assertThat(observer.takeNext()).isEmpty();
    observer.assertOnCompleted();
  }

  @Test public void interactionsWithDeletedListFail() {
    ListStore<TestData> store = storeProvider.listStore("testValues", TestData.class);
    List<TestData> values = Arrays.asList(new TestData("Test1", 1), new TestData("Test2", 2));
    store.put(values);
    store.delete();

    String expectedMessage = "This store has been deleted!";

    Throwable getError = store.get()
        .toObservable()
        .materialize()
        .filter(new Func1<Notification<List<TestData>>, Boolean>() {
          @Override public Boolean call(Notification<List<TestData>> notification) {
            return notification.isOnError();
          }
        })
        .map(new Func1<Notification<List<TestData>>, Throwable>() {
          @Override public Throwable call(Notification<List<TestData>> notification) {
            return notification.getThrowable();
          }
        })
        .toBlocking()
        .single();

    assertThat(getError).hasMessage(expectedMessage);

    Throwable putError = store.observePut(Collections.singletonList(new TestData("Test3", 3)))
        .toObservable()
        .materialize()
        .filter(new Func1<Notification<List<TestData>>, Boolean>() {
          @Override public Boolean call(Notification<List<TestData>> notification) {
            return notification.isOnError();
          }
        })
        .map(new Func1<Notification<List<TestData>>, Throwable>() {
          @Override public Throwable call(Notification<List<TestData>> notification) {
            return notification.getThrowable();
          }
        })
        .toBlocking()
        .single();

    assertThat(putError).hasMessage(expectedMessage);

    Throwable clearError = store.observeClear()
        .toObservable()
        .materialize()
        .filter(new Func1<Notification<List<TestData>>, Boolean>() {
          @Override public Boolean call(Notification<List<TestData>> notification) {
            return notification.isOnError();
          }
        })
        .map(new Func1<Notification<List<TestData>>, Throwable>() {
          @Override public Throwable call(Notification<List<TestData>> notification) {
            return notification.getThrowable();
          }
        })
        .toBlocking()
        .single();

    assertThat(clearError).hasMessage(expectedMessage);
  }

  private static class TestData {
    public final String string;
    public final int integer;

    TestData(String string, int integer) {
      this.string = string;
      this.integer = integer;
    }

    @Override public boolean equals(Object o) {
      if (!(o instanceof TestData)) {
        return false;
      }

      TestData otherData = (TestData) o;

      if (string != null) {
        return string.equals(otherData.string) && integer == otherData.integer;
      }

      return otherData.string == null && integer == otherData.integer;
    }

    @Override public String toString() {
      return string + "," + integer;
    }

    public static TestData fromString(String string) {
      String[] splitString = string.split(",");
      return new TestData(splitString[0], Integer.parseInt(splitString[1]));
    }
  }

  private static class TestConverter implements Converter {
    @Override public <T> void write(T data, Type type, File file) throws ConverterException {
      try {
        Writer writer = new FileWriter(file);

        if (data == null) {
          writer.write("");
          writer.close();
        } else if (data instanceof TestData) {
          writer.write(data.toString());
          writer.close();
        } else if (data instanceof List) {
          @SuppressWarnings("unchecked") List<TestData> dataList = (List<TestData>) data;

          for (int i = 0, n = dataList.size(); i < n; i++) {
            if (i != 0) {
              // Separate each TestData instance by a "~" character.
              writer.write("~");
            }

            writer.write(dataList.get(i).toString());
          }

          writer.close();
        }
      } catch (Exception e) {
        throw new ConverterException(e);
      }
    }

    @Override public <T> T read(File file, Type type) throws ConverterException {
      try {
        String storedString = new BufferedReader(new FileReader(file)).readLine();

        if (isBlank(storedString)) return null;

        if (type instanceof StoreProvider.ListType) {
          // Stored string contains each TestData separated by a "~" character.
          String[] splitString = storedString.split("~");
          List<TestData> list = new ArrayList<>(splitString.length);

          for (String itemString : splitString) {
            list.add(TestData.fromString(itemString));
          }

          //noinspection unchecked
          return (T) list;
        } else {
          //noinspection unchecked
          return (T) TestData.fromString(storedString);
        }
      } catch (Exception e) {
        throw new ConverterException(e);
      }
    }
  }

  private static boolean isBlank(String string) {
    return string == null || string.trim().length() == 0;
  }
}
