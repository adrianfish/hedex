package org.sakaiproject.hedex.impl;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.Set;

import org.sakaiproject.assignment.api.AssignmentService;
import org.sakaiproject.assignment.api.AssignmentConstants;
import org.sakaiproject.assignment.api.AssignmentReferenceReckoner;
import org.sakaiproject.assignment.api.model.Assignment;
import org.sakaiproject.assignment.api.model.AssignmentSubmission;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.event.api.Event;
import org.sakaiproject.event.api.EventTrackingService;
import org.sakaiproject.event.api.UsageSessionService;
import org.sakaiproject.presence.api.PresenceService;

import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.criterion.Restrictions;

import org.sakaiproject.hedex.api.model.AssignmentSubmissions;
import org.sakaiproject.hedex.api.model.CourseVisits;
import org.sakaiproject.hedex.api.model.SessionDuration;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HedexEventDigester implements Observer {

    private final String PRESENCE_SUFFIX = "-presence";

    private final List<String> handledEvents
        = Arrays.asList(UsageSessionService.EVENT_LOGIN
                        , UsageSessionService.EVENT_LOGOUT
                        , AssignmentConstants.EVENT_GRADE_ASSIGNMENT_SUBMISSION
                        , AssignmentConstants.EVENT_SUBMIT_ASSIGNMENT_SUBMISSION
                        , PresenceService.EVENT_PRESENCE);

    @Setter
    private EventTrackingService eventTrackingService;

    @Setter
    private ServerConfigurationService serverConfigurationService;

    @Setter
    private SessionFactory sessionFactory;

    @Setter
    private AssignmentService assignmentService;

    public void init() {

        log.debug("HedexEventDigester.init()");

        log.debug("Handled events: {}", String.join(",", handledEvents));
        eventTrackingService.addObserver(this);
    }

    public void update(Observable o, final Object arg) {

        if (arg instanceof Event) {
            Event event = (Event) arg;
            String eventName = event.getEvent();
            if (handledEvents.contains(eventName)) {

                log.debug("Handling event '{}' ...", eventName);

                String sessionId = event.getSessionId();
                String eventUserId = event.getUserId();
                String reference = event.getResource();

                if (UsageSessionService.EVENT_LOGIN.equals(eventName)) {
                    try {
                        SessionDuration sd = new SessionDuration();
                        sd.setUserId(eventUserId);
                        sd.setSessionId(sessionId);
                        sd.setStartTime(event.getEventTime());
                        Session session = sessionFactory.openSession();
                        Transaction tx = session.beginTransaction();
                        session.save(sd);
                        tx.commit();
                    } catch (Exception e) {
                        log.error("Failed to in insert new SessionDuration", e);
                    }
                } else if (UsageSessionService.EVENT_LOGOUT.equals(eventName)) {
                    try {
                        Session session = sessionFactory.openSession();
                        List<SessionDuration> sessionDurations = session.createCriteria(SessionDuration.class)
                            .add(Restrictions.eq("sessionId", sessionId)).list();
                        if (sessionDurations.size() != 1) {
                        } else {
                            SessionDuration sd = sessionDurations.get(0);
                            sd.setDuration(event.getEventTime().getTime() - sd.getStartTime().getTime());
                            Transaction tx = session.beginTransaction();
                            session.update(sd);
                            tx.commit();
                        }
                    } catch (Exception e) {
                        log.error("Failed to in insert new SessionDuration", e);
                    }
                } else if (AssignmentConstants.EVENT_SUBMIT_ASSIGNMENT_SUBMISSION.equals(eventName)) {
                    // We need to check for the fully formed submit event.
                    if (reference.contains("/")) {
                        AssignmentReferenceReckoner.AssignmentReference submissionReference
                            = AssignmentReferenceReckoner
                                .newAssignmentReferenceReckoner(null, null, null, null, null, reference, null);
                        String siteId = event.getContext();
                        String assignmentId = submissionReference.getContainer();
                        String submissionId = submissionReference.getId();
                        try {
                            Assignment assignment = assignmentService.getAssignment(assignmentId);

                            Assignment.GradeType gradeType = assignment.getTypeOfGrade();
                            // Lookup the current AssignmentSubmissions record. There should only
                            // be <= 1 for this user and assignment.
                            Session session = sessionFactory.openSession();
                            List<AssignmentSubmissions> assignmentSubmissionss
                                = session.createCriteria(AssignmentSubmissions.class)
                                    .add(Restrictions.eq("userId", eventUserId))
                                    .add(Restrictions.eq("assignmentId", assignmentId))
                                    .add(Restrictions.eq("submissionId", submissionId)).list();
                            if (assignmentSubmissionss.size() != 1) {
                                // No record yet. Create one.
                                AssignmentSubmissions as = new AssignmentSubmissions();
                                as.setUserId(eventUserId);
                                as.setAssignmentId(assignmentId);
                                as.setSubmissionId(submissionId);
                                as.setSiteId(siteId);
                                as.setTitle(assignment.getTitle());
                                as.setDueDate(Date.from(assignment.getDueDate()));
                                as.setNumSubmissions(1);

                                Transaction tx = session.beginTransaction();
                                session.save(as);
                                tx.commit();
                            } else {
                                AssignmentSubmissions as = assignmentSubmissionss.get(0);
                                as.setNumSubmissions(as.getNumSubmissions() + 1);
                                Transaction tx = session.beginTransaction();
                                session.save(as);
                                tx.commit();
                            }
                        } catch (Exception e) {
                            log.error("Failed to in insert/update AssignmentSubmissions", e);
                        }
                    }
                } else if (AssignmentConstants.EVENT_GRADE_ASSIGNMENT_SUBMISSION.equals(eventName)) {
                    AssignmentReferenceReckoner.AssignmentReference submissionReference
                        = AssignmentReferenceReckoner
                            .newAssignmentReferenceReckoner(null, null, null, null, null, reference, null);

                    String siteId = submissionReference.getContext();
                    String assignmentId = submissionReference.getContainer();
                    String submissionId = submissionReference.getId();

                    try {
                        Assignment assignment = assignmentService.getAssignment(assignmentId);
                        AssignmentSubmission submission = assignmentService.getSubmission(submissionId);
                        Assignment.GradeType gradeType = assignment.getTypeOfGrade();
                        // Lookup the current AssignmentSubmissions record. There should only
                        // be <= 1 for this user and submission.
                        Session session = sessionFactory.openSession();
                        log.debug("Searching for record for user id {} and assignment id {}", eventUserId, assignmentId);
                        List<AssignmentSubmissions> assignmentSubmissionss
                            = session.createCriteria(AssignmentSubmissions.class)
                                //.add(Restrictions.eq("userId", eventUserId))
                                .add(Restrictions.eq("assignmentId", assignmentId))
                                .add(Restrictions.eq("submissionId", submissionId)).list();
                        if (assignmentSubmissionss.size() != 1) {
                        } else {
                            AssignmentSubmissions as = assignmentSubmissionss.get(0);
                            String grade = submission.getGrade();
                            if (as.getFirstScore() == null) {
                                as.setFirstScore(grade);
                                as.setLastScore(grade);
                                if (gradeType.equals(Assignment.GradeType.SCORE_GRADE_TYPE)) {
                                    // This is a numeric grade, so we can do numeric stuff with it.
                                    try {
                                        int numericScore = Integer.parseInt(grade);
                                        as.setLowestScore(numericScore);
                                        as.setHighestScore(numericScore);
                                        as.setAverageScore((float)numericScore);
                                    } catch (NumberFormatException nfe) {
                                    }
                                }
                            } else {
                                as.setLastScore(grade);
                                if (gradeType.equals(Assignment.GradeType.SCORE_GRADE_TYPE)) {
                                    // This is a numeric grade, so we can do numeric stuff with it.
                                    try {
                                        int numericScore = Integer.parseInt(grade);
                                        if (numericScore < as.getLowestScore()) as.setLowestScore(numericScore);
                                        else if (numericScore > as.getHighestScore()) as.setHighestScore(numericScore);
                                        as.setAverageScore((float)((as.getLowestScore() + as.getHighestScore()) / 2));
                                    } catch (NumberFormatException nfe) {
                                    }
                                }
                            }
                            Transaction tx = session.beginTransaction();
                            session.save(as);
                            tx.commit();
                        }
                    } catch (Exception e) {
                        log.error("Failed to in insert/update AssignmentSubmissions", e);
                    }
                } else if (PresenceService.EVENT_PRESENCE.equals(eventName)) {
                    // Parse out the course id
                    String compoundId = reference.substring(reference.lastIndexOf("/") + 1);
                    String siteId = compoundId.substring(0, compoundId.indexOf(PRESENCE_SUFFIX));
                    System.out.println("SITE ID: " + siteId);

                    if (siteId.startsWith("~")) {
                        // This is a user workspace
                        return;
                    }

                    Session session = sessionFactory.openSession();

                    List<CourseVisits> courseVisitss = session.createCriteria(CourseVisits.class)
                        .add(Restrictions.eq("userId", eventUserId))
                        .add(Restrictions.eq("siteId", siteId)).list();

                    CourseVisits courseVisits = null;

                    if (courseVisitss.size() == 1) {
                        courseVisits = courseVisitss.get(0);
                        courseVisits.setNumVisits(courseVisits.getNumVisits() + 1L);
                    } else {
                        courseVisits = new CourseVisits();
                        courseVisits.setUserId(eventUserId);
                        courseVisits.setSiteId(siteId);
                        courseVisits.setNumVisits(1L);
                    }
                    courseVisits.setLatestVisit(event.getEventTime());
                    Transaction tx = session.beginTransaction();
                    session.save(courseVisits);
                    tx.commit();
                }
            }
        }
    }
}
