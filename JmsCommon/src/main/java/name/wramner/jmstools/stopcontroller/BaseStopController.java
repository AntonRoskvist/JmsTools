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
package name.wramner.jmstools.stopcontroller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for stop controllers that handles the task of waking waiting threads up when the sub-class signals that it
 * is time to stop by returning false from {@link #shouldKeepRunning()}.
 * 
 * @author Erik Wramner
 */
public abstract class BaseStopController implements StopController {
    protected final Logger _logger = LoggerFactory.getLogger(getClass());
    private final Object _monitor = new Object();

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean keepRunning() {
        if (shouldKeepRunning()) {
            return true;
        }
        _logger.debug("Stop controller done, releasing waiting threads");
        releaseWaitingThreads();
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void waitForTimeoutOrDone(long timeToWaitMillis) {
        synchronized (_monitor) {
            try {
                if (keepRunning()) {
                    _monitor.wait(timeToWaitMillis);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Check if done.
     * 
     * @return true to keep running, false when done.
     */
    protected abstract boolean shouldKeepRunning();

    /**
     * Release any threads that are waiting for the stop controller.
     */
    private void releaseWaitingThreads() {
        synchronized (_monitor) {
            _monitor.notifyAll();
        }
    }
}