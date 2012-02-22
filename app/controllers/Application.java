package controllers;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jobs.Downloader;
import jobs.Downloader.Format;
import jobs.Exporter;
import models.Instance;
import models.Patient;
import models.Person;
import models.Project;
import models.ProjectAssociation;
import models.Series;
import models.Study;
import notifiers.Mail;

import org.apache.commons.lang.StringUtils;
import org.dcm4che.data.Dataset;

import play.Play;
import play.cache.Cache;
import play.libs.IO;
import play.mvc.Before;
import play.mvc.Finally;
import util.Clipboard;
import util.Dicom;
import util.PersistentLogger;
import util.Properties;
import controllers.Secure.Security;

public class Application extends SecureController {
	//session variables
	public static final String CLIPBOARD = "clipboard";
	public static final String EXPORTS = "exports";

	@Before
	static void before() {
		if (Security.isConnected()) {
			renderArgs.put(CLIPBOARD, new Clipboard(getUser().clipboard));
			renderArgs.put(EXPORTS, Exporter.getExports(session));
		}
	}

	public static void index() {
		render();
	}

	public static void admin() {
		render();
	}

	public static void help() {
		render();
	}

	private static Map<String, String> comparators = new HashMap<String, String>() {{
		put("before", "<");
		put("on", "=");
		put("after", ">");
		put("since", ">");
	}};
	public static void studies(String name, String id, Integer age, Character sex, String protocol, String acquisition, String study, int page, String order, String sort, Long project, String participationID) throws Exception {
		List<String> from = new ArrayList<String>();
		from.add("Study study");

		List<String> where = new ArrayList<String>();
		List<Object> args = new ArrayList<Object>();

		if (!name.isEmpty()) {
			where.add("lower(patient.pat_name) like ?");
			args.add("%" + name.toLowerCase() + "%");
		}
		if (!id.isEmpty()) {
			where.add("(lower(patient.pat_id) like ? or lower(study_custom1) like ?)");
			args.add("%" + id.toLowerCase() + "%");
			args.add("%" + id.toLowerCase() + "%");
		}
		if (age != null) {
			where.add("cast(study_datetime as date) - cast(patient.pat_birthdate as date) >= ? and cast(study_datetime as date) - cast(patient.pat_birthdate as date) < ?");
			args.add(365D * age);
			args.add(365D * (age + 1));
		}
		if (sex != null) {
			where.add("patient.pat_sex = ?");
			args.add(sex);
		}
		if (!protocol.isEmpty()) {
			from.add("in(study.series) series");
			where.add("lower(series.series_custom1) like ?");
			args.add("%" + protocol.toLowerCase() + "%");
		}
		if (!study.isEmpty()) {
			where.add("lower(study_desc) like ?");
			args.add("%" + study.toLowerCase() + "%");
		}
		if (!acquisition.isEmpty()) {
			//where.add(String.format("(study_datetime is null or cast(study_datetime as date) %s ?)", comparators.get(acquisition)));
			where.add(String.format("cast(study_datetime as date) %s ?", comparators.get(acquisition)));
			args.add(params.get(acquisition, Date.class));
		}
		if (project != null || !participationID.isEmpty()) {
			from.add("in (study.projectAssociations) association");
			if (project != null) {
				where.add("association.project.id = ?");
				args.add(project);
			}
			if (!participationID.isEmpty()) {
				where.add("association.participationID = ?");
				args.add(participationID);
			}
		}

		String query = "select study from " + StringUtils.join(from, ", ");
		if (!where.isEmpty()) {
			query += " where " + StringUtils.join(where, " and ");
		}
		query += " order by " + "study." + (order.isEmpty() ? "patient.pk" : order) + " " + ("desc".equals(sort) ? "desc" : "asc");
		List<Study> studies = Study.find(query, args.toArray()).fetch(page + 1, Properties.getInt("page.size"));
		int studyCount = Study.find(query, args.toArray()).fetch().size();
		render(studies, studyCount, page);
	}

	public static void patient(long pk) throws Exception {
		Patient patient = Patient.findById(pk);
		render(patient);
	}

	public static void series(long pk) throws IOException {
		Series series = Series.findById(pk);
		Dataset dataset = Dicom.dataset(Dicom.file(series.instances.iterator().next()));
		Set<String> echoes = Dicom.echoes(dataset);
		render(series, dataset, echoes);
	}

	public static void image(long pk, Integer columns, int echo) throws MalformedURLException, IOException {
		Series series = Series.findById(pk);
		if (!Dicom.renderable(series)) {
			renderBinary(new File(Play.applicationPath, "public/images/128x128.gif"));
		}
		int frameNumber;
		String objectUID = null;
		if (series.instances.size() == 1) {
			frameNumber = Integer.parseInt(Dicom.attribute(series.instances.iterator().next().inst_attrs, "NumberOfFrames")) / 2 + echo + 1;
			objectUID = series.instances.iterator().next().sop_iuid;
		} else {
			frameNumber = 1;
			objectUID = Instance.find("bySeriesAndInst_no", series, String.valueOf(series.instances.size() / 2)).<Instance>first().sop_iuid;
		}
		String url = String.format("http://%s:8080/wado?requestType=WADO&studyUID=&seriesUID=&objectUID=%s&frameNumber=%s", request.domain, objectUID, frameNumber);
		if (columns != null) {
			url += String.format("&columns=%s", columns);
		}
		IO.copy(new URL(url).openConnection().getInputStream(), response.out);
	}

	public static void download(long pk, Format format, String echo) throws InterruptedException, IOException {
		PersistentLogger.log("downloaded series %s", pk);
		File tmpDir = new File(Properties.getDownloads(), UUID.randomUUID().toString());
		tmpDir.mkdir();
		renderBinary(await(new Downloader(pk, format == null ? Format.dcm : format, echo, tmpDir).now()));
	}

	public static void export(String password) throws InterruptedException, IOException, ClassNotFoundException {
		PersistentLogger.log("exported clipboard %s", getUser().clipboard);
		Clipboard clipboard = (Clipboard) renderArgs.get(CLIPBOARD);
		File tmpDir = new File(Properties.getDownloads(), UUID.randomUUID().toString());
		tmpDir.mkdir();
		new Exporter(clipboard, tmpDir, password, session, getUser().username).now();
		clipboard(null, null, null);
	}

	public static void retrieve(String filename) {
		File download = Exporter.getExport(filename, session);
		notFoundIfNull(download);
		renderBinary(download);
	}

	public enum ClipboardOp { ADD, REMOVE, CLEAR }
	public static void clipboard(ClipboardOp op, String type, Long pk) throws ClassNotFoundException {
		Clipboard clipboard = (Clipboard) renderArgs.get(CLIPBOARD);
		if (op != null) {
			switch (op) {
			case ADD: clipboard.add(type, pk); break;
			case REMOVE: clipboard.remove(type, pk); break;
			case CLEAR: clipboard.clear(); break;
			}
			Person person = getUser();
			person.clipboard = clipboard.toString();
			person.merge()._save();
			Cache.delete(Security.connected());
		}
		render();
	}

	public static void imagej(long pk, String echo) throws InterruptedException, IOException {
		Series series = Series.findById(pk);
		File tmpDir = new File(Properties.getDownloads(), UUID.randomUUID().toString());
		tmpDir.mkdir();
		File dcm;
		if (series.instances.size() == 1) {
			//TODO this doesn't handle multi-echo (neither does export)
			dcm = await(new Downloader(pk, Format.dcm, echo, tmpDir).now());
		} else {
			//TODO this doesn't handle multi-echo either (but export does)
			File nii = await(new Downloader(pk, Format.nii, echo, tmpDir).now());
			dcm = new File(tmpDir, String.format("%s.dcm", series.toDownloadString()));
			ProcessBuilder pb = new ProcessBuilder(new File(Properties.getString("xmedcon"), "bin/medcon").getPath(),
					"-c", "dicom",
					"-noprefix",
					"-anon",
					"-fv",
					"-o", dcm.getPath(),
					"-f", nii.getPath()
					);
			pb.environment().put("LD_LIBRARY_PATH", new File(Properties.getString("xmedcon"), "lib").getPath());
			pb.start().waitFor();
		}
		renderBinary(dcm);
	}

	public static void associate(Study study, Long projectID, String participationID, String projectName) {
		ProjectAssociation association = ProjectAssociation.find("from ProjectAssociation where study = ? and project.person = ?", study, getUser()).first();
		if (association != null && projectID == null) {
			association.delete();
		}
		Project project = null;
		if (!projectName.isEmpty()) {
			project = new Project(projectName, getUser()).save();
		} else if (projectID != null) {
			project = Project.findById(projectID);
		}
		if (project != null) {
			if (association != null) {
				association.project = project;
			} else {
				association = new ProjectAssociation(project, study);
			}
			association.participationID = participationID;
			association.save();
		}
		redirect(request.headers.get("referer").value());
	}

	@Finally
	static void log(Throwable e) {
		if (e != null && Properties.getString("mail.from") != null) {
			Mail.exception(request, session, e);
		}
	}
}