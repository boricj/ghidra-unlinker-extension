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
package ghidra.app.analyzers;

import static ghidra.app.util.ProgramUtil.parseAddressSet;
import static ghidra.app.util.ProgramUtil.serializeAddressSet;

import java.awt.Component;
import java.beans.PropertyEditorSupport;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import ghidra.app.analyzers.relocations.synthesizers.CodeRelocationSynthesizer;
import ghidra.app.analyzers.relocations.synthesizers.DataRelocationSynthesizer;
import ghidra.app.services.AbstractAnalyzer;
import ghidra.app.services.AnalysisPriority;
import ghidra.app.services.AnalyzerType;
import ghidra.app.util.AddressSetEditorPanel;
import ghidra.app.util.importer.MessageLog;
import ghidra.framework.options.OptionType;
import ghidra.framework.options.Options;
import ghidra.program.model.address.AddressFactory;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.relocobj.RelocationTable;
import ghidra.util.classfinder.ClassSearcher;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

public class RelocationTableSynthesizerAnalyzer extends AbstractAnalyzer {
	public class AddressSetPropertyEditor extends PropertyEditorSupport {
		private final Program program;
		private final AddressSetEditorPanel panel;

		public AddressSetPropertyEditor(Program program, AddressSetView addressSet) {
			this.program = program;
			this.panel = new AddressSetEditorPanel(program.getAddressFactory(), addressSet);
			this.panel.addChangeListener(new ChangeListener() {
				@Override
				public void stateChanged(ChangeEvent e) {
					setValue(serializeAddressSet(panel.getAddressSetView()));
				}
			});
		}

		@Override
		public Component getCustomEditor() {
			return panel;
		}

		@Override
		public boolean supportsCustomEditor() {
			return true;
		}

		@Override
		public void setAsText(String text) {
			setValue(parseAddressSet(text, program.getAddressFactory()));
		}
	}

	private final static String NAME = "Relocation table synthesizer";
	private final static String DESCRIPTION =
		"Synthesize a relocation table for this program";

	private final static String OPTION_NAME_RELOCATABLE_ADDRESS_RANGES =
		"Relocatable address ranges";

	private final static String OPTION_DESCRIPTION_RELOCATABLE_ADDRESS_RANGES =
		"Set of address ranges that are eligible as targets for relocations. If empty, the entire program is considered relocatable.";

	private AddressSetView relocatableTargets;

	public RelocationTableSynthesizerAnalyzer() {
		super(NAME, DESCRIPTION, AnalyzerType.BYTE_ANALYZER);
		setDefaultEnablement(false);
		setPriority(AnalysisPriority.LOW_PRIORITY);
		setPrototype();
		setSupportsOneTimeAnalysis();
	}

	public static List<CodeRelocationSynthesizer> getCodeSynthesizers(Program program) {
		return ClassSearcher.getInstances(CodeRelocationSynthesizer.class)
				.stream()
				.filter(s -> s.canAnalyze(program))
				.collect(Collectors.toList());
	}

	public static List<DataRelocationSynthesizer> getDataSynthesizers(Program program) {
		return ClassSearcher.getInstances(DataRelocationSynthesizer.class)
				.stream()
				.filter(s -> s.canAnalyze(program))
				.collect(Collectors.toList());
	}

	@Override
	public boolean added(Program program, AddressSetView set, TaskMonitor monitor, MessageLog log)
			throws CancelledException {
		RelocationTable relocationTable = RelocationTable.get(program);
		relocationTable.clear(set);

		Listing listing = program.getListing();
		FunctionManager functionManager = program.getFunctionManager();

		AddressSetView relocatable = relocatableTargets;
		if (relocatable == null) {
			relocatable = program.getAddressFactory().getAddressSet();
		}

		List<CodeRelocationSynthesizer> codeSynthesizers = getCodeSynthesizers(program);
		if (codeSynthesizers.isEmpty()) {
			log.appendMsg(getClass().getSimpleName(),
				"No code relocation synthesizers found for this processor!");
		}
		List<DataRelocationSynthesizer> dataSynthesizers = getDataSynthesizers(program);
		if (dataSynthesizers.isEmpty()) {
			log.appendMsg(getClass().getSimpleName(),
				"No data relocation synthesizers found for this processor!");
		}

		monitor.setMessage("Relocation table synthesizer: compute work size");
		monitor.setIndeterminate(true);
		monitor.setMaximum(calculateMaximumProgress(program, set));
		monitor.setProgress(0);
		monitor.setIndeterminate(false);

		for (Function function : functionManager.getFunctions(set, true)) {
			monitor.setMessage("Relocation table synthesizer: " + function.getName(true));

			processFunction(codeSynthesizers, relocatable, function, relocationTable, monitor, log);

			monitor.incrementProgress(function.getBody().getNumAddresses());
			monitor.checkCancelled();
		}

		for (Data data : listing.getDefinedData(set, true)) {
			monitor.setMessage(
				"Relocation table synthesizer: " + data.getAddressString(true, true));

			processData(dataSynthesizers, relocatable, data, relocationTable, monitor, log);

			monitor.incrementProgress(data.getLength());
			monitor.checkCancelled();
		}

		return true;
	}

	private static void processFunction(List<CodeRelocationSynthesizer> synthesizers,
			AddressSetView relocatable, Function function, RelocationTable relocationTable,
			TaskMonitor monitor, MessageLog log) throws CancelledException {
		for (CodeRelocationSynthesizer synthesizer : synthesizers) {
			try {
				synthesizer.process(function.getProgram(), relocatable, function, relocationTable,
					monitor, log);
			}
			catch (MemoryAccessException e) {
				log.appendException(e);
			}
		}
	}

	private static void processData(List<DataRelocationSynthesizer> synthesizers,
			AddressSetView relocatable, Data parent, RelocationTable relocationTable,
			TaskMonitor monitor, MessageLog log) {
		if (parent.isPointer()) {
			for (DataRelocationSynthesizer synthesizer : synthesizers) {
				try {
					synthesizer.process(parent.getProgram(), relocatable, parent, relocationTable,
						monitor, log);
				}
				catch (MemoryAccessException e) {
					log.appendException(e);
				}
			}
		}
		else if (parent.isArray() && parent.getNumComponents() >= 1) {
			Data data = parent.getComponent(0);

			if (data.isPointer() || data.isArray() || data.isStructure()) {
				for (int i = 0; i < parent.getNumComponents(); i++) {
					processData(synthesizers, relocatable, parent.getComponent(i), relocationTable,
						monitor, log);
				}
			}
		}
		else if (parent.isStructure()) {
			for (int i = 0; i < parent.getNumComponents(); i++) {
				processData(synthesizers, relocatable, parent.getComponent(i), relocationTable,
					monitor, log);
			}
		}
	}

	private static long calculateMaximumProgress(Program program, AddressSetView set) {
		Listing listing = program.getListing();
		FunctionManager functionManager = program.getFunctionManager();

		long progressSize = 0;

		for (Function function : functionManager.getFunctions(set, true)) {
			progressSize += function.getBody().getNumAddresses();
		}

		for (Data data : listing.getDefinedData(set, true)) {
			progressSize += data.getLength();
		}

		return progressSize;
	}

	@Override
	public void registerOptions(Options options, Program program) {
		options.registerOption(OPTION_NAME_RELOCATABLE_ADDRESS_RANGES, OptionType.STRING_TYPE, "[]",
			null, OPTION_DESCRIPTION_RELOCATABLE_ADDRESS_RANGES,
			() -> new AddressSetPropertyEditor(program, relocatableTargets));
	}

	@Override
	public void optionsChanged(Options options, Program program) {
		AddressFactory addressFactory = program.getAddressFactory();

		relocatableTargets = parseAddressSet(
			options.getString(OPTION_NAME_RELOCATABLE_ADDRESS_RANGES, "[]"), addressFactory);
	}

	@Override
	public boolean canAnalyze(Program program) {
		return !getCodeSynthesizers(program).isEmpty() || !getDataSynthesizers(program).isEmpty();
	}
}
