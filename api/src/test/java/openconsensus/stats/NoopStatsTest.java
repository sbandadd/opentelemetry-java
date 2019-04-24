/*
 * Copyright 2019, OpenConsensus Authors
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

package openconsensus.stats;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nullable;
import openconsensus.stats.Measure.MeasureDouble;
import openconsensus.stats.Measurement.MeasurementDouble;
import openconsensus.tags.Tag;
import openconsensus.tags.TagKey;
import openconsensus.tags.TagMap;
import openconsensus.tags.TagValue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link NoopStats}. */
@RunWith(JUnit4.class)
public final class NoopStatsTest {
  private static final Tag TAG =
      Tag.create(
          TagKey.create("key"), TagValue.create("value"), Tag.METADATA_UNLIMITED_PROPAGATION);
  private static final MeasureDouble MEASURE =
      MeasureDouble.create("my measure", "description", "s");

  private final TagMap tagMap =
      new TagMap() {

        @Override
        public Iterator<Tag> getIterator() {
          return Collections.<Tag>singleton(TAG).iterator();
        }

        @Nullable
        @Override
        public TagValue getTagValue(TagKey tagKey) {
          return TagValue.create("value");
        }
      };

  @Rule public final ExpectedException thrown = ExpectedException.none();

  @Test
  public void noopStatsRecorder_PutNegativeValue() {
    List<Measurement> measurements =
        Collections.<Measurement>singletonList(MeasurementDouble.create(MEASURE, -5));
    NoopStats.newNoopStatsRecorder().record(measurements, tagMap);
  }

  // The NoopStatsRecorder should do nothing, so this test just checks that record doesn't throw an
  // exception.
  @Test
  public void noopStatsRecorder_Record() {
    List<Measurement> measurements =
        Collections.<Measurement>singletonList(MeasurementDouble.create(MEASURE, 5));
    NoopStats.newNoopStatsRecorder().record(measurements, tagMap);
  }

  // The NoopStatsRecorder should do nothing, so this test just checks that record doesn't throw an
  // exception.
  @Test
  public void noopStatsRecorder_RecordWithCurrentContext() {
    List<Measurement> measurements =
        Collections.<Measurement>singletonList(MeasurementDouble.create(MEASURE, 6));
    NoopStats.newNoopStatsRecorder().record(measurements);
  }

  @Test
  public void noopStatsRecorder_Record_DisallowNulltagMap() {
    List<Measurement> measurements =
        Collections.<Measurement>singletonList(MeasurementDouble.create(MEASURE, 6));
    thrown.expect(NullPointerException.class);
    thrown.expectMessage("tags");
    NoopStats.newNoopStatsRecorder().record(measurements, null);
  }
}