/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.redhat.demo.optaplanner.restapi;

public class DispatchMechanicResponse extends AbstractResponse {

    private int mechanicIndex;
    private int newFocusMachineIndex;

    public DispatchMechanicResponse() {
        super(ResponseType.DISPATCH_MECHANIC);
    }

    public DispatchMechanicResponse(int mechanicIndex, int newFocusMachineIndex) {
        super(ResponseType.DISPATCH_MECHANIC);
        this.mechanicIndex = mechanicIndex;
        this.newFocusMachineIndex = newFocusMachineIndex;
    }

    public int getMechanicIndex() {
        return mechanicIndex;
    }

    public void setMechanicIndex(int mechanicIndex) {
        this.mechanicIndex = mechanicIndex;
    }

    public int getNewFocusMachineIndex() {
        return newFocusMachineIndex;
    }

    public void setNewFocusMachineIndex(int newFocusMachineIndex) {
        this.newFocusMachineIndex = newFocusMachineIndex;
    }

}
