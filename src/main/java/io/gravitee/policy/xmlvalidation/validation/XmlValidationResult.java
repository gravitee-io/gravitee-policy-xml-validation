/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
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
package io.gravitee.policy.xmlvalidation.validation;

import java.util.List;
import java.util.stream.Collectors;

public final class XmlValidationResult {

    private final boolean valid;
    private final List<XmlValidationViolation> violations;

    private XmlValidationResult(boolean valid, List<XmlValidationViolation> violations) {
        this.valid = valid;
        this.violations = violations;
    }

    public static XmlValidationResult success() {
        return new XmlValidationResult(true, List.of());
    }

    public static XmlValidationResult failure(List<XmlValidationViolation> violations) {
        return new XmlValidationResult(false, List.copyOf(violations));
    }

    public boolean isValid() {
        return valid;
    }

    public List<XmlValidationViolation> violations() {
        return violations;
    }

    public String summary() {
        return violations.stream().map(XmlValidationViolation::formatted).collect(Collectors.joining("; "));
    }
}
