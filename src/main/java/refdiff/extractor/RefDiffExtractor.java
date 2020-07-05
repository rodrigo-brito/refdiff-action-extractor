package refdiff.extractor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import refdiff.core.cst.CstNode;
import refdiff.core.cst.Location;
import refdiff.core.diff.CstComparator.DiffBuilder;
import refdiff.core.diff.CstDiff;
import refdiff.core.diff.Relationship;
import refdiff.core.io.GitHelper;
import refdiff.parsers.LanguagePlugin;
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
		if (ref == null || ref.isEmpty()) {
			System.err.println("Invalid PR reference: "+ref);
	    	return;
		}
		
		Pattern pattern = Pattern.compile("pull/(\\d+)");
	    Matcher matcher = pattern.matcher(ref);
	    if (!matcher.find()) {
	    	System.err.println("Invalid PR reference: "+ref);
	    	return;
	    }
	    
	    int PR = Integer.valueOf(matcher.group(1));
	    System.out.println("PR_NUMBER = "+PR);


		try {
			TimeLimitedCodeBlock.runWithTimeout(new Runnable() {
				@Override
				public void run() {
					try {
						Task task = new Task(System.getenv("GITHUB_WORKSPACE") +"/.git", System.getenv("REV_BEFORE"), System.getenv("REV_AFTER"), PR);
						task.run();
					}
					catch (Exception e) {
						System.err.println("Error on proccess refactorings: " + e.getMessage());
					}
				}
			}, 60, TimeUnit.SECONDS);
		}
		catch (TimeoutException e) {
			System.out.println("Timeout :(");
		}
	}
}

class Task {
	private static String language;
	private static LanguagePlugin plugin;
	private static String repositoryPath;
	private static String reference;
	private static String commitAfter;
	private static int PR;
	
	public Task(String repositoryPath, String reference, String commitAfter, int PR) {
		Task.repositoryPath = repositoryPath;
		Task.reference = reference;
		Task.commitAfter = commitAfter;
		Task.PR = PR;
	}

	public void run() throws Exception {
		String credentialsFile = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
		
		Firestore db = null;
		if (credentialsFile != null && !credentialsFile.isEmpty()) {
			GoogleCredentials credentials = GoogleCredentials.getApplicationDefault(); // file from GOOGLE_APPLICATION_CREDENTIALS
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
		List<Refactoring> refactorings = getRefactorings(refdiff, repo, diff, head, revAfter);
		if (credentialsFile != null && !credentialsFile.isEmpty()) {
			saveCutomServer(db, refactorings, PR, docID, executionTime);
		} else {
//				savePublicServer(refactorings, PR, docID, executionTime);
		}
		
		for(RevCommit commit: Git.wrap(repo).log().addRange(head, revAfter).call()) {
			if (commit.getParentCount() > 1) {
				continue;
			}
			
			startTime = System.currentTimeMillis();
			diff = refdiff.computeDiffForCommit(new File(repositoryPath), commit.name());
			executionTime = System.currentTimeMillis() - startTime;
			
			docID = String.format("%s/%s/commit/%s",repositoryNameParts[0], repositoryNameParts[1], commit.name());
			refactorings = getRefactorings(refdiff, repo, diff, commit.getParent(0), commit);
			
			if (credentialsFile != null && !credentialsFile.isEmpty()) {
				saveCutomServer(db, refactorings, PR, docID, executionTime);
			} else {
//					savePublicServer(refactorings, PR, docID, executionTime);
			}
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
	
	private static List<Refactoring> getRefactorings(RefDiff refdiff, Repository repo, CstDiff diff, RevCommit revBefore, RevCommit revAfter) {
		List<Refactoring> refactorings = new ArrayList<>();
		DiffBuilder<?> diffBuilder = refdiff.getComparator().getDiffBuilder();
		for (Relationship rel : diff.getRefactoringRelationships()) {
			try {
				Refactoring refactoring = Refactoring.FromRelationship(rel);
				if (refactoring.getType().contains("MOVE")) {
					String beforeCode = getFileCode(repo, revBefore, refactoring.getBeforeFileName()).substring(refactoring.getBeforeBegin(), refactoring.getBeforeEnd());
					String afterCode = getFileCode(repo, revAfter, refactoring.getAfterFileName()).substring(refactoring.getAfterBegin(), refactoring.getAfterEnd());
					refactoring.setDiff(getDiffinGitFormat(beforeCode, afterCode));
				} else if (refactoring.getType().contains("EXTRACT")) {
					java.util.Optional<CstNode> nodeAfter = diffBuilder.matchingNodeAfter(rel.getNodeBefore());
					if (nodeAfter.isPresent()) {
						Location location = nodeAfter.get().getLocation();
						String beforeCode = getFileCode(repo, revBefore, refactoring.getBeforeFileName()).substring(refactoring.getBeforeBegin(), refactoring.getBeforeEnd());
						String afterCode = getFileCode(repo, revAfter, location.getFile()).substring(location.getBegin(),location.getEnd());
						String extraction = getFileCode(repo, revAfter, refactoring.getAfterFileName()).substring(refactoring.getAfterBegin(), refactoring.getAfterEnd());
						refactoring.setDiff(getDiffinGitFormat(beforeCode, afterCode));
						refactoring.setExtraction(extraction);
					}
				}
				refactorings.add(refactoring);
			} catch (Exception e) {
				System.err.println("Error on parse refactoring: "+ e.getMessage());
			}
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
		data.put("language", language);
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
		data.put("language", language);
		data.put("execution_time", executionTime);
		data.put("refactorings", refactorings);
		data.put("created_at", now);
		String content = new Gson().toJson(data);
		
		System.out.println("Sending to RefDiff server: " + docID);
		HttpPost post = new HttpPost("INVALID"); // TODO: fix
		try {
			StringEntity entity = new StringEntity(content, "UTF-8");
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


class TimeLimitedCodeBlock {
	  public static void runWithTimeout(final Runnable runnable, long timeout, TimeUnit timeUnit) throws Exception {
	    runWithTimeout(new Callable<Object>() {
	      @Override
	      public Object call() throws Exception {
	        runnable.run();
	        return null;
	      }
	    }, timeout, timeUnit);
	  }

	  public static <T> T runWithTimeout(Callable<T> callable, long timeout, TimeUnit timeUnit) throws Exception {
	    final ExecutorService executor = Executors.newSingleThreadExecutor();
	    final Future<T> future = executor.submit(callable);
	    executor.shutdown(); // This does not cancel the already-scheduled task.
	    try {
	      return future.get(timeout, timeUnit);
	    }
	    catch (TimeoutException e) {
	      //remove this if you do not want to cancel the job in progress
	      //or set the argument to 'false' if you do not want to interrupt the thread
	      future.cancel(true);
	      throw e;
	    }
	    catch (ExecutionException e) {
	      //unwrap the root cause
	      Throwable t = e.getCause();
	      if (t instanceof Error) {
	        throw (Error) t;
	      } else if (t instanceof Exception) {
	        throw (Exception) t;
	      } else {
	        throw new IllegalStateException(t);
	      }
	    }
	  }

	}
