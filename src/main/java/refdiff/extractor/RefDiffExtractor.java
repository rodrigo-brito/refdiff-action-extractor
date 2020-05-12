package refdiff.extractor;

import java.io.File;
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
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

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
import refdiff.core.diff.CstDiff;
import refdiff.core.diff.Relationship;
import refdiff.core.io.GitHelper;
import refdiff.parsers.c.CPlugin;
import refdiff.parsers.go.GoPlugin;
import refdiff.parsers.java.JavaPlugin;
import refdiff.parsers.js.JsPlugin;

public class RefDiffExtractor {
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
		
		File tempFolder = new File("temp");
		RefDiff refdiff = null;

		String[] repositoryNameParts = System.getenv("GITHUB_REPOSITORY").split("/");
		if (repositoryNameParts.length != 2) {
			System.out.println(String.format("invalide project name %s, it should be in format user/repository", System.getenv("GITHUB_REPOSITORY")));
		}
		
		String language = "java";
		if (System.getenv("LANGUAGE") != null) {
			language = System.getenv("LANGUAGE").toLowerCase();
		}
		
		switch (language) {
		case "javascript":
			refdiff = new RefDiff(new JsPlugin());
			break;
		case "c":
			refdiff = new RefDiff(new CPlugin());
			break;
		case "go":
			refdiff = new RefDiff(new GoPlugin(tempFolder));
			break;
		case "java":
			refdiff = new RefDiff(new JavaPlugin(tempFolder));
			break;
		default:
			System.out.println(String.format("Language %s is not supported.", language));
			return;
		}
		
		try {
			long startTime = System.currentTimeMillis();

			Repository repo = GitHelper.openRepository(new File(repositoryPath));
			RevWalk rw = new RevWalk(repo);
			
			RevCommit revAfter = rw.parseCommit(repo.resolve(commitAfter));
			RevCommit head = rw.parseCommit(repo.resolve(reference));
			
			// Compute with refdiff
			CstDiff diff = refdiff.computeDiffBetweenRevisions(repo, head, revAfter);
			printRefactorings(diff);
			long executionTime = System.currentTimeMillis() - startTime;
			
			String docID = String.format("%s/%s/pull/%d", repositoryNameParts[0], repositoryNameParts[1], PR);
			if (credentialsFile != null && !credentialsFile.isEmpty()) {
				saveCutomServer(db, diff, PR, docID, executionTime);
			} else {
				savePublicServer(diff, PR, docID, executionTime);
			}
			
			for(RevCommit commit: Git.wrap(repo).log().addRange(head, revAfter).call()) {
				startTime = System.currentTimeMillis();
				CstDiff commitDiff = refdiff.computeDiffForCommit(new File(repositoryPath), commit.name());
				executionTime = System.currentTimeMillis() - startTime;
				docID = String.format("%s/%s/commit/%s",repositoryNameParts[0], repositoryNameParts[1], commit.name());
				if (credentialsFile != null && !credentialsFile.isEmpty()) {
					saveCutomServer(db, commitDiff, PR, docID, executionTime);
				} else {
					savePublicServer(diff, PR, docID, executionTime);
				}
			}
			
			
		} catch (Exception e) {
			System.err.println("Error on get content from commits: "+e.getMessage());
		}
		System.out.println("Finished!");
	}

	
	private static void saveCutomServer(Firestore db, CstDiff diff, int PR, String docID, long executionTime) throws InterruptedException, ExecutionException, IOException {
		if (diff.getRefactoringRelationships().size() == 0) {
			return;
		}
	
		System.out.println("Sending to firebase: " + docID);
	
		List<HashMap<String, Object>> refactorings = new ArrayList<>();		
		for (Relationship rel : diff.getRefactoringRelationships()) {
			Refactoring refactoring = Refactoring.FromRelationship(rel);
			refactorings.add(refactoring.toHashMap());
		}
		

		Timestamp now = new Timestamp(new Date().getTime());
		Map<String, Object> data = new HashMap<>();
		data.put("pr", PR);
		data.put("execution_time", executionTime);
		data.put("refactorings", refactorings);
		data.put("created_at", now);
		
		DocumentReference docRef = db.document(docID);		
		ApiFuture<WriteResult> result = docRef.set(data);
		System.out.println("Saved at "+result.get().getUpdateTime());
	}
	
	private static void savePublicServer(CstDiff diff, int PR, String docID, long executionTime) {
		System.out.println("Sending to RefDiff server: " + docID);
		if (diff.getRefactoringRelationships().size() == 0) {
			return;
		}
		
		List<Refactoring> refactorings = new ArrayList<>();		
		for (Relationship rel : diff.getRefactoringRelationships()) {
			Refactoring refactoring = Refactoring.FromRelationship(rel);
			refactorings.add(refactoring);
		}
		
		String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date());
		Map<String, Object> data = new HashMap<>();
		data.put("id", docID);
		data.put("pr", PR);
		data.put("execution_time", executionTime);
		data.put("refactorings", refactorings);
		data.put("created_at", now);
		String content = new Gson().toJson(data);
		
		HttpPost post = new HttpPost("http://refdiff.brito.com.br/new");
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
