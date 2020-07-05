package refdiff.extractor;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.annotations.SerializedName;

import refdiff.core.diff.Relationship;

public class Refactoring {
	@SerializedName("type")
	private String type;
	@SerializedName("object_type")
	private String objectType;

	@SerializedName("before_local_name")
	private String beforeLocalName;
	@SerializedName("before_file_name")
	private String beforeFileName;
	@SerializedName("before_begin")
	private int beforeBegin;
	@SerializedName("before_end")
	private int beforeEnd;
	@SerializedName("before_line_number")
	private int beforeLineNumber;

	@SerializedName("after_local_name")
	private String afterLocalName;
	@SerializedName("after_file_name")
	private String afterFileName;
	@SerializedName("after_begin")
	private int afterBegin;
	@SerializedName("after_end")
	private int afterEnd;
	@SerializedName("after_line_number")
	private int afterLineNumber;
	@SerializedName("diff")
	private String diff;
	@SerializedName("extraction")
	private String extraction;

	public static Refactoring FromRelationship(Relationship rel) {
		Refactoring refactoring = new Refactoring();
		refactoring.type = rel.getType().toString();
		refactoring.objectType = rel.getNodeAfter().getType().replace("Declaration", "").toUpperCase();
		
		refactoring.beforeLocalName = rel.getNodeBefore().getLocalName();
		refactoring.beforeBegin = rel.getNodeBefore().getLocation().getBegin();
		refactoring.beforeEnd = rel.getNodeBefore().getLocation().getEnd();
		refactoring.beforeFileName = rel.getNodeBefore().getLocation().getFile();
		refactoring.beforeLineNumber = rel.getNodeBefore().getLocation().getLine();

		refactoring.afterLocalName = rel.getNodeAfter().getLocalName();
		refactoring.afterBegin = rel.getNodeAfter().getLocation().getBegin();
		refactoring.afterEnd = rel.getNodeAfter().getLocation().getEnd();
		refactoring.afterFileName = rel.getNodeAfter().getLocation().getFile();
		refactoring.afterLineNumber = rel.getNodeAfter().getLocation().getLine();
	
		return refactoring;
	}
	
	public String getDiff() {
		return diff;
	}

	public void setDiff(String diff) {
		this.diff = diff;
	}
	
	public String getExtraction() {
		return extraction;
	}

	public void setExtraction(String extraction) {
		this.extraction = extraction;
	}


	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getObjectType() {
		return objectType;
	}

	public void setObjectType(String objectType) {
		this.objectType = objectType;
	}

	public String getBeforeLocalName() {
		return beforeLocalName;
	}

	public void setBeforeLocalName(String beforeLocalName) {
		this.beforeLocalName = beforeLocalName;
	}

	public String getBeforeFileName() {
		return beforeFileName;
	}

	public void setBeforeFileName(String beforeFileName) {
		this.beforeFileName = beforeFileName;
	}

	public int getBeforeBegin() {
		return beforeBegin;
	}

	public void setBeforeBegin(int beforeBegin) {
		this.beforeBegin = beforeBegin;
	}

	public int getBeforeEnd() {
		return beforeEnd;
	}

	public void setBeforeEnd(int beforeEnd) {
		this.beforeEnd = beforeEnd;
	}

	public int getBeforeLineNumber() {
		return beforeLineNumber;
	}

	public void setBeforeLineNumber(int beforeLineNumber) {
		this.beforeLineNumber = beforeLineNumber;
	}

	public String getAfterLocalName() {
		return afterLocalName;
	}

	public void setAfterLocalName(String afterLocalName) {
		this.afterLocalName = afterLocalName;
	}

	public String getAfterFileName() {
		return afterFileName;
	}

	public void setAfterFileName(String afterFileName) {
		this.afterFileName = afterFileName;
	}

	public int getAfterBegin() {
		return afterBegin;
	}

	public void setAfterBegin(int afterBegin) {
		this.afterBegin = afterBegin;
	}

	public int getAfterEnd() {
		return afterEnd;
	}

	public void setAfterEnd(int afterEnd) {
		this.afterEnd = afterEnd;
	}

	public int getAfterLineNumber() {
		return afterLineNumber;
	}

	public void setAfterLineNumber(int afterLineNumber) {
		this.afterLineNumber = afterLineNumber;
	}

	@Override
	public String toString() {
		return String.format("Type: %s %s | Before: %s %s:%d | After: %s %s:%d ", this.type, this.objectType, this.beforeLocalName, this.beforeFileName,
				this.beforeLineNumber, this.afterLocalName, this.afterFileName, this.afterLineNumber);
	}
	
	public HashMap<String, Object> toHashMap() {
		HashMap<String, Object> map = new HashMap<>();
		map.put("type", this.type);
		map.put("object_type", this.objectType);
		map.put("before_local_name", this.beforeLocalName);
		map.put("before_file_name", this.beforeFileName);
		map.put("before_begin", this.beforeBegin);
		map.put("before_end", this.beforeEnd);
		map.put("before_line_number", this.beforeLineNumber);
		map.put("after_local_name", this.afterLocalName);
		map.put("after_file_name", this.afterFileName);
		map.put("after_begin", this.afterBegin);
		map.put("after_end", this.afterEnd);
		map.put("after_line_number", this.afterLineNumber);
		if (this.diff != null && !this.diff.isEmpty()) {
			map.put("diff", this.diff);			
		}
		if (this.extraction != null && !this.extraction.isEmpty()) {
			map.put("extraction", this.extraction);			
		}
		return map;
	}
	
	
}
