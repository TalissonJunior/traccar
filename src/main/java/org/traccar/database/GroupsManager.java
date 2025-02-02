/*
 * Copyright 2017 - 2022 Anton Tananaev (anton@traccar.org)
 * Copyright 2017 Andrey Kunitsyn (andrey@traccar.org)
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
package org.traccar.database;

import java.util.HashSet;
import java.util.Set;

import org.traccar.model.Group;
import org.traccar.storage.StorageException;

public class GroupsManager extends BaseObjectManager<Group> {

    public GroupsManager(DataManager dataManager) {
        super(dataManager, Group.class);
    }

    private void checkGroupCycles(Group group) {
        Set<Long> groups = new HashSet<>();
        while (group != null) {
            if (groups.contains(group.getId())) {
                throw new IllegalArgumentException("Cycle in group hierarchy");
            }
            groups.add(group.getId());
            group = getById(group.getGroupId());
        }
    }

    @Override
    public Set<Long> getAllItems() {
        Set<Long> result = super.getAllItems();
        if (result.isEmpty()) {
            refreshItems();
            result = super.getAllItems();
        }
        return result;
    }

    @Override
    protected void addNewItem(Group group) {
        checkGroupCycles(group);
        super.addNewItem(group);
    }

    @Override
    public void updateItem(Group group) throws StorageException {
        checkGroupCycles(group);
        super.updateItem(group);
    }

}
