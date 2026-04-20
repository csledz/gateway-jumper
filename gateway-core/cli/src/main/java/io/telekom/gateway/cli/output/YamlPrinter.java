// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Block-style YAML via Jackson's YAMLFactory — no {@code ---} document start marker. */
public final class YamlPrinter extends JacksonPrinter {

  private static final ObjectWriter WRITER =
      new ObjectMapper(
              new YAMLFactory()
                  .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                  .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES))
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .writer();

  public YamlPrinter() {
    super(WRITER);
  }
}
