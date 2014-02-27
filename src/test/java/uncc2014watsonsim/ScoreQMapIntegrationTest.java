package uncc2014watsonsim;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.util.List;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.json.simple.parser.ParseException;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.fluent.*;

import static org.junit.Assert.*;
import static org.hamcrest.CoreMatchers.*;

import org.junit.Test;

public class ScoreQMapIntegrationTest {
	@Test
	public void integrate() throws FileNotFoundException, ParseException, IOException {
		QuestionMap questionmap = StatsGenerator.getQuestionMap();
		Question question = questionmap.get("This London borough is the G in GMT squire");
		new AverageScorer().test(question);
		String top_answer = question.get(0).getTitle();
		assertNotNull(top_answer);
		assertThat(top_answer.length(), not(0));
	}

	@Test
	public void sample() throws FileNotFoundException, ParseException, IOException, GitAPIException {
		new StatsGenerator().run();
	}
}

/**
 * @author Phani Rahul
 * @author Sean Gallagher
 */
class StatsGenerator {
	/** Fetch the sample data from the Internet
	 * @throws IOException 
	 * @throws ClientProtocolException 
	 * @throws ParseException */
	static QuestionMap getQuestionMap() throws ClientProtocolException, IOException, ParseException {
		try (Reader reader = SampleData.get("main_v1.0.json.gz")) {
			QuestionMap questionmap = new QuestionMap(reader);
			return questionmap;
		}
	}
	QuestionMap questionmap;
	// correct[n] =def= number of correct answers at rank n 
	int[] correct = new int[100];
	int available = 0;
	double total_inverse_rank = 0;
	int total_answers = 0;
	
	double runtime;
	int[] conf_correct = new int[100];
	int[] conf_hist = new int[100];
	
	public StatsGenerator() throws ClientProtocolException, IOException, ParseException {
		questionmap = getQuestionMap();
	}
	
	/** Measure how accurate the top question is as a histogram across confidence */
	public void calculateConfidenceHistogram(Question question) {
		if (question.size() >= 1) {
			// Supposing there is at least one answer
			ResultSet rs = question.get(0);
			// Clamp to [0, 99]
			int bin = (int)(rs.first("combined").score * 100);
			if(rs.isCorrect()) conf_correct[bin]++;
			conf_hist[bin]++;
		}
	}

	private String join(int[] arr) {
		String out = "";
		for (int a: arr) {
			out += String.valueOf(a) + " ";
		}
		return out;
	}
	
	public void onCorrectAnswer(ResultSet answer, int rank) {
		total_inverse_rank += 1 / ((double)rank + 1);
		available++;
		correct[rank]++;
	}
	
	private void report() throws IOException {
		// Gather git information
		String branch, commit;
		if (System.getenv("TRAVIS_BRANCH") != null) { 
			branch = System.getenv("TRAVIS_BRANCH");
			commit = System.getenv("TRAVIS_COMMIT");
		} else {
		  	Repository repo = new FileRepositoryBuilder().readEnvironment().findGitDir().build();
		   	commit = repo.resolve("HEAD").abbreviate(10).name();
		   	if (commit == null)
		   		fail("Problem finding git repository. Not submitting stats.");
			branch = repo.getBranch();
		}

		// Generate report
		List<NameValuePair> response = Form.form()
				.add("run[branch]", branch)
				.add("run[commit_hash]", commit.substring(0, 10))
				.add("run[dataset]", "main") // NOTE: Fill this in if you change it
				.add("run[top]", String.valueOf(correct[0]))
				.add("run[top3]", String.valueOf(correct[0] + correct[1] + correct[2]))
				.add("run[available]", String.valueOf(available))
				.add("run[rank]", String.valueOf(total_inverse_rank))
				.add("run[total_questions]", String.valueOf(questionmap.size()))
				.add("run[total_answers]", String.valueOf(total_answers))
				.add("run[confidence_histogram]", join(conf_hist))
				.add("run[confidence_correct_histogram]", join(conf_correct))
				.add("run[runtime]", String.valueOf(runtime))
				.build();
		System.out.println(response);
		Request.Post("http://watsonsim.herokuapp.com/runs.json").bodyForm(response).execute();
		
	
		System.out.println("" + correct[0] + " of " + questionmap.size() + " correct");
		System.out.println("" + available + " of " + questionmap.size() + " could have been");
		System.out.println("Mean Inverse Rank " + total_inverse_rank);
	}

	public void run() throws IOException {
		long start_time = System.nanoTime();
		for (Question question : questionmap.values()) {
			new AverageScorer().test(question);
			ResultSet top_answer = question.get(0);
			assertNotNull(top_answer);
			assertThat(top_answer.getTitle().length(), not(0));
	
			for (int rank=0; rank<question.size(); rank++) {
				ResultSet answer = question.get(rank);
				if(answer.isCorrect()) {
					onCorrectAnswer(answer, rank);
					break;
				}
			}
			
			calculateConfidenceHistogram(question);
			
			total_answers += question.size();
			//System.out.println("Q: " + text.question + "\n" +
			//		"A[Guessed: " + top_answer.getScore() + "]: " + top_answer.getTitle() + "\n" +
			//		"A[Actual:" + correct_answer_score + "]: "  + text.answer);
		}
	
		// Only count the rank of questions that were actually there
		total_inverse_rank /= available;
		// Finish the timing
		runtime = System.nanoTime() - start_time;
		runtime /= 1e9;
		report();
	}
}
