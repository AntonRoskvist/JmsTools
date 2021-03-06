/*
 * Copyright 2016 Erik Wramner.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package name.wramner.jmstools.messages;

import javax.jms.BytesMessage;

/**
 * Message data for JMS {@link BytesMessage} messages.
 */
public class BytesMessageData extends ChecksummedMessageData {
    private final byte[] _data;

    /**
     * Constructor.
     * 
     * @param data The payload.
     */
    public BytesMessageData(byte[] data) {
        super(data);
        _data = data;
    }

    /**
     * Get payload.
     * 
     * @return payload.
     */
    public byte[] getData() {
        return _data;
    }
}