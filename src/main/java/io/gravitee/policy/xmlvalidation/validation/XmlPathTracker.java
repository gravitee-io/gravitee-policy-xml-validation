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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.XMLFilterImpl;

/**
 * Tracks the current element path while the payload is parsed for XSD validation.
 * Repeated siblings are distinguished ({@code /order/item[2]}).
 */
final class XmlPathTracker extends XMLFilterImpl {

    private final Deque<Frame> stack = new ArrayDeque<>();

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
        String name = localName != null && !localName.isBlank() ? localName : qName;
        Frame parent = stack.peekLast();
        int index = 1;
        if (parent != null) {
            index = parent.childCounts.merge(name, 1, Integer::sum);
        }
        stack.addLast(new Frame(name, index));
        super.startElement(uri, localName, qName, atts);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        super.endElement(uri, localName, qName);
        if (!stack.isEmpty()) {
            stack.removeLast();
        }
    }

    String currentPath() {
        if (stack.isEmpty()) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (Frame frame : stack) {
            path.append('/').append(frame.name);
            if (frame.index > 1) {
                path.append('[').append(frame.index).append(']');
            }
        }
        return path.toString();
    }

    private static final class Frame {

        private final String name;
        private final int index;
        private final Map<String, Integer> childCounts = new HashMap<>();

        private Frame(String name, int index) {
            this.name = name;
            this.index = index;
        }
    }
}
