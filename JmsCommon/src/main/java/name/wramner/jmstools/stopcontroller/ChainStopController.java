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

/**
 * Stop controller that evaluates a chain of stop controllers and keeps running as long as at least one controller in
 * the chain votes yes.
 * 
 * @author Erik Wramner
 */
public class ChainStopController extends BaseStopController {
    private final StopController[] _controllers;

    /**
     * Constructor.
     * 
     * @param controllers Chain with controllers to evaluate in order.
     */
    public ChainStopController(StopController... controllers) {
        _controllers = controllers;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean shouldKeepRunning() {
        for (StopController c : _controllers) {
            if (c.keepRunning()) {
                return true;
            }
        }
        return false;
    }
}
