package refdiff.extractor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.HttpEntity;
import org.apache.http.ParseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import com.google.api.client.util.Charsets;
import com.google.api.core.ApiFuture;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteResult;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import com.google.gson.Gson;

import refdiff.core.RefDiff;
import refdiff.core.diff.CstComparator;
import refdiff.core.diff.CstComparator.DiffBuilder;
import refdiff.core.diff.CstComparatorMonitor;
import refdiff.core.diff.CstDiff;
import refdiff.core.diff.CstRootHelper;
import refdiff.core.diff.Relationship;
import refdiff.core.diff.similarity.TfIdfSourceRepresentation;
import refdiff.core.diff.similarity.TfIdfSourceRepresentationBuilder;
import refdiff.core.io.GitHelper;
import refdiff.core.io.SourceFile;
import refdiff.core.io.SourceFileSet;
import refdiff.core.util.PairBeforeAfter;
import refdiff.parsers.LanguagePlugin;
import refdiff.parsers.c.CPlugin;
import refdiff.parsers.go.GoPlugin;
import refdiff.parsers.java.JavaPlugin;
import refdiff.parsers.js.JsPlugin;

public class RefDiffExtractor {
	private static LanguagePlugin plugin;
	public static void main(String[] args) throws Exception {
		System.out.println("ENVIRONMENT");
		System.out.println("--------");
		System.out.println("GITHUB_REPOSITORY: " +System.getenv("GITHUB_REPOSITORY"));
		System.out.println("GITHUB_WORKSPACE: " +System.getenv("GITHUB_WORKSPACE"));
		System.out.println("GITHUB_REF: " +System.getenv("GITHUB_REF"));
		System.out.println("REV_BEFORE: " +System.getenv("REV_BEFORE"));
		System.out.println("REV_AFTER: " +System.getenv("REV_AFTER"));
		System.out.println("LANGUAGE: " +System.getenv("LANGUAGE"));
		
		String ref = System.getenv("GITHUB_REF");
		Pattern pattern = Pattern.compile("pull/(\\d+)");
	    Matcher matcher = pattern.matcher(ref);
	    if (!matcher.find()) {
	    	System.err.println("Invalid PR reference: "+ref);
	    	return;
	    }
	    
	    int PR = Integer.valueOf(matcher.group(1));
	    System.out.println("PR_NUMBER = "+PR);
		
		scanRefactorings(System.getenv("GITHUB_WORKSPACE") +"/.git", System.getenv("REV_BEFORE"), System.getenv("REV_AFTER"), PR);
	}

	private static void scanRefactorings(String repositoryPath, String reference, String commitAfter, int PR) throws Exception {
		String credentialsFile = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
		
		Firestore db = null;
		if (credentialsFile != null && !credentialsFile.isEmpty()) {
			GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
			FirebaseOptions options = new FirebaseOptions.Builder()
					.setCredentials(credentials)
					.build();
			FirebaseApp.initializeApp(options);
			db = FirestoreClient.getFirestore();
		}

		String[] repositoryNameParts = System.getenv("GITHUB_REPOSITORY").split("/");
		if (repositoryNameParts.length != 2) {
			System.out.println(String.format("invalide project name %s, it should be in format user/repository", System.getenv("GITHUB_REPOSITORY")));
			return;
		}
		
		String language = "java";
		if (System.getenv("LANGUAGE") != null) {
			language = System.getenv("LANGUAGE").toLowerCase();
		}
		
		File tempFolder = new File("temp");
		
		switch (language) {
		case "javascript":
			plugin = new JsPlugin();
			break;
		case "c":
			plugin = new CPlugin();
			break;
		case "go":
			plugin = new GoPlugin(tempFolder);
			break;
		case "java":
			plugin = new JavaPlugin(tempFolder);
			break;
		default:
			System.out.println(String.format("Language %s is not supported.", language));
			return;
		}
		
		
		try {
			RefDiff refdiff = new RefDiff(plugin);
			long startTime = System.currentTimeMillis();

			Repository repo = GitHelper.openRepository(new File(repositoryPath));
			RevWalk rw = new RevWalk(repo);
			
			RevCommit revAfter = rw.parseCommit(repo.resolve(commitAfter));
			RevCommit head = rw.parseCommit(repo.resolve(reference));
			
			CstDiff diff = refdiff.computeDiffBetweenRevisions(repo, head, revAfter);
			printRefactorings(diff);
			long executionTime = System.currentTimeMillis() - startTime;			
			
			String docID = String.format("%s/%s/pull/%d", repositoryNameParts[0], repositoryNameParts[1], PR);
			List<Refactoring> refactorings = getRefactorings(repo, diff, head, revAfter);
			if (credentialsFile != null && !credentialsFile.isEmpty()) {
				saveCutomServer(db, refactorings, PR, docID, executionTime);
			} else {
				savePublicServer(refactorings, PR, docID, executionTime);
			}
			
			for(RevCommit commit: Git.wrap(repo).log().addRange(head, revAfter).call()) {
				startTime = System.currentTimeMillis();
				CstDiff commitDiff = refdiff.computeDiffForCommit(new File(repositoryPath), commit.name());
				executionTime = System.currentTimeMillis() - startTime;
				
				docID = String.format("%s/%s/commit/%s",repositoryNameParts[0], repositoryNameParts[1], commit.name());
				refactorings = getRefactorings(repo, diff, commit.getParent(0), commit);
				
				if (credentialsFile != null && !credentialsFile.isEmpty()) {
					saveCutomServer(db, refactorings, PR, docID, executionTime);
				} else {
					savePublicServer(refactorings, PR, docID, executionTime);
				}
			}			
		} catch (Exception e) {
			System.err.println("Error on get content from commits: "+e.getMessage());
		}
		System.out.println("Finished!");
	}
	
	private static String getFileCode(Repository repo, RevCommit commit, String path) throws IOException {
		try (ObjectReader reader = repo.newObjectReader(); RevWalk walk = new RevWalk(reader)) {
			RevTree tree = commit.getTree();
			TreeWalk treewalk = TreeWalk.forPath(reader, path, tree);

			if (treewalk != null) {
				byte[] content = reader.open(treewalk.getObjectId(0)).getBytes();
				return new String(content, "utf-8");
			} else {
				throw new FileNotFoundException(path);
			}
		}
	}
	
	private static List<Refactoring> getRefactorings(Repository repo, CstDiff diff, RevCommit revBefore, RevCommit revAfter) throws IOException {
		List<Refactoring> refactorings = new ArrayList<>();
		 
		for (Relationship rel : diff.getRefactoringRelationships()) {
			Refactoring refactoring = Refactoring.FromRelationship(rel); // node before here
			fillRefactoringDiff(repo, revBefore, revAfter, refactoring);
			refactorings.add(refactoring);
		}
		 
		return refactorings;
	}
	
	private static String getDiffinGitFormat(String beforeConent, String afterContent) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		RawText beforeRawText = new RawText(beforeConent.getBytes(Charsets.UTF_8));
		RawText afeterRawText = new RawText(afterContent.getBytes(Charsets.UTF_8));
		EditList diffList = new EditList();
		diffList.addAll(new HistogramDiff().diff(RawTextComparator.DEFAULT, beforeRawText, afeterRawText));
		new DiffFormatter(out).format(diffList, beforeRawText, afeterRawText);
		return out.toString(Charsets.UTF_8.name());
	}
	
	private static void fillRefactoringDiff(Repository repo, RevCommit revBefore, RevCommit revAfter, Refactoring refactoring) throws IOException {
		if (!refactoring.getType().contains("MOVE") &&	!refactoring.getType().contains("EXTRACT") &&
				!refactoring.getType().contains("INLINE")) {
			return;
		}
		

		PairBeforeAfter<SourceFileSet> beforeAndAfter = GitHelper.getSourcesBeforeAndAfterCommit(repo, head, revAfter, plugin.getAllowedFilesFilter());
		CstComparator comparator = new CstComparator(plugin);
		DiffBuilder<?> diffBuilder = comparator.new DiffBuilder<TfIdfSourceRepresentation>(new TfIdfSourceRepresentationBuilder(), beforeAndAfter.getBefore(),
				beforeAndAfter.getAfter(), new CstComparatorMonitor() {});
		
		CstRootHelper<?> before = diffBuilder.getAfter();
		CstRootHelper<?> after = diffBuilder.getAfter();
		
		before.bodySourceRep(refactoring.)
		
		String beforeCode = getFileCode(repo, revBefore, refactoring.getBeforeFileName()).substring(refactoring.getBeforeBegin(), refactoring.getBeforeEnd());
		String afterCode = String.valueOf(getFileCode(repo, revAfter, refactoring.getAfterFileName())).substring(refactoring.getAfterBegin(), refactoring.getAfterEnd());
	
		refactoring.setDiff(getDiffinGitFormat(beforeCode, afterCode));
	}

	
	private static void saveCutomServer(Firestore db, List<Refactoring> refactorings, int PR, String docID, long executionTime) throws InterruptedException, ExecutionException, IOException {
		if (refactorings.size() == 0) {
			return;
		}
	
		List<HashMap<String, Object>> refactoringsMap = new ArrayList<>();		
		for (Refactoring refactoring: refactorings) {
			refactoringsMap.add(refactoring.toHashMap());
		}
		

		Timestamp now = new Timestamp(new Date().getTime());
		Map<String, Object> data = new HashMap<>();
		data.put("pr", PR);
		data.put("execution_time", executionTime);
		data.put("refactorings", refactoringsMap);
		data.put("created_at", now);
		
		System.out.println("Sending to firebase: " + docID);
		DocumentReference docRef = db.document(docID);		
		ApiFuture<WriteResult> result = docRef.set(data);
		result.get().getUpdateTime();
	}
	
	private static void savePublicServer(List<Refactoring> refactorings, int PR, String docID, long executionTime) {
		if (refactorings.size() == 0) {
			return;
		}
		
		String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date());
		Map<String, Object> data = new HashMap<>();
		data.put("id", docID);
		data.put("pr", PR);
		data.put("execution_time", executionTime);
		data.put("refactorings", refactorings);
		data.put("created_at", now);
		String content = new Gson().toJson(data);
		
		System.out.println("Sending to RefDiff server: " + docID);
		HttpPost post = new HttpPost("http://localhost:8080/new");
		try {
			HttpEntity entity = new StringEntity(content);
	        post.setEntity(entity);
	        CloseableHttpClient httpClient = HttpClients.createDefault();
	        CloseableHttpResponse response = httpClient.execute(post);
	        if (response.getStatusLine().getStatusCode() >= 400) {
	        	System.out.println("Error on save: " + response.getStatusLine());
	        }
	        
	        
		} catch (ParseException | IOException e) {
			System.err.println("Error on save pull request data: " + e.getMessage());
		}
	}
	
	private static void printRefactorings(CstDiff diff) {
		if (diff.getRefactoringRelationships().size() > 0) {
			System.out.println("\nREFACTORINGS");
			System.out.println("-------------");
			for (Relationship rel : diff.getRefactoringRelationships()) {
				System.out.println(Refactoring.FromRelationship(rel));
			}
			System.out.println("-------------");
		}
	}

}
