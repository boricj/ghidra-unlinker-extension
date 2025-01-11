/*
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
package ghidra.app.analyzers.relocations.synthesizers;

import ghidra.app.util.importer.MessageLog;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.relocobj.RelocationTable;
import ghidra.util.classfinder.ExtensionPoint;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * This interface is for analyzing relocation spots within functions.
 */
public interface CodeRelocationSynthesizer extends ExtensionPoint {
	public void process(Program program, AddressSetView relocatable, Function function,
			RelocationTable relocationTable, TaskMonitor monitor, MessageLog log)
			throws MemoryAccessException, CancelledException;

	public boolean canAnalyze(Program program);
}
