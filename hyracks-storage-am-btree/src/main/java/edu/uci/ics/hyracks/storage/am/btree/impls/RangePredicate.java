/*
 * Copyright 2009-2010 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.hyracks.storage.am.btree.impls;

import edu.uci.ics.hyracks.dataflow.common.data.accessors.ITupleReference;
import edu.uci.ics.hyracks.storage.am.btree.api.ISearchPredicate;

public class RangePredicate implements ISearchPredicate {
		
	private static final long serialVersionUID = 1L;
	
	protected boolean isForward = true;
	protected ITupleReference lowKey = null;
	protected ITupleReference highKey = null;
	protected MultiComparator cmp;
	
	public RangePredicate() {
	}
	
	// TODO: for now range is [lowKey, highKey] but depending on user predicate the range could be exclusive on any end
	// need to model this somehow	
	// for point queries just use same value for low and high key
	public RangePredicate(boolean isForward, ITupleReference lowKey, ITupleReference highKey, MultiComparator cmp) {
		this.isForward = isForward;
		this.lowKey = lowKey;
		this.highKey = highKey;
		this.cmp = cmp;
	}
	
	public MultiComparator getComparator() {
		return cmp;
	}
	
	public void setComparator(MultiComparator cmp) {
		this.cmp = cmp;
	}
	
	public boolean isForward() {
		return isForward;
	}	
	
	public ITupleReference getLowKey() {
		return lowKey;
	}
	
	public ITupleReference getHighKey() {
		return highKey;
	}
}