import { createAction } from "redux-actions";
import { MetabotApi } from "metabase/services";
import { closeNavbar } from "metabase/redux/app";
import { MetabotFeedbackType } from "metabase-types/api";
import {
  Dispatch,
  GetState,
  MetabotEntityId,
  MetabotEntityType,
} from "metabase-types/store";
import Question from "metabase-lib/Question";
import {
  getEntityId,
  getEntityType,
  getFeedbackType,
  getNativeQueryText,
  getPrompt,
  getQuestion,
} from "./selectors";

export interface InitPayload {
  entityId: MetabotEntityId;
  entityType: MetabotEntityType;
  initialPrompt?: string;
}

export const INIT = "metabase/metabot/INIT";
export const init = (payload: InitPayload) => (dispatch: Dispatch) => {
  dispatch({ type: INIT, payload });
  dispatch(closeNavbar());

  if (payload.initialPrompt) {
    dispatch(runPromptQuery());
  }
};

export const RESET = "metabase/metabot/RESET";
export const reset = createAction(RESET);

export const UPDATE_QUESTION = "metabase/metabot/UPDATE_QUESTION";
export const updateQuestion = createAction(
  UPDATE_QUESTION,
  (question: Question) => question.card(),
);

export const UPDATE_PROMPT = "metabase/metabot/UPDATE_PROMPT";
export const updatePrompt = createAction(UPDATE_PROMPT);

export const RUN_PROMPT_QUERY = "metabase/metabot/RUN_PROMPT_QUERY";
export const RUN_PROMPT_QUERY_FULFILLED =
  "metabase/metabot/RUN_PROMPT_QUERY_FULFILLED";
export const RUN_PROMPT_QUERY_REJECTED =
  "metabase/metabot/RUN_PROMPT_QUERY_REJECTED";
export const runPromptQuery = () => async (dispatch: Dispatch) => {
  try {
    dispatch({ type: RUN_PROMPT_QUERY });
    await dispatch(fetchQuestion());
    await dispatch(fetchQueryResults());
    dispatch({ type: RUN_PROMPT_QUERY_FULFILLED });
  } catch (error) {
    dispatch({ type: RUN_PROMPT_QUERY_REJECTED, payload: error });
  }
};

export const RUN_QUESTION_QUERY = "metabase/metabot/RUN_QUESTION_QUERY";
export const RUN_QUESTION_QUERY_FULFILLED =
  "metabase/metabot/RUN_QUESTION_QUERY_FULFILLED";
export const RUN_QUESTION_QUERY_REJECTED =
  "metabase/metabot/RUN_QUESTION_QUERY_REJECTED";
export const runQuestionQuery = () => async (dispatch: Dispatch) => {
  try {
    dispatch({ type: RUN_QUESTION_QUERY });
    await dispatch(fetchQueryResults());
    dispatch({ type: RUN_QUESTION_QUERY_FULFILLED });
  } catch (error) {
    dispatch({ type: RUN_QUESTION_QUERY_REJECTED, payload: error });
  }
};

export const cancelQuery = () => async () => undefined;

export const FETCH_QUESTION = "metabase/metabot/FETCH_QUESTION";
export const fetchQuestion =
  () => async (dispatch: Dispatch, getState: GetState) => {
    const entityId = getEntityId(getState());
    const entityType = getEntityType(getState());
    const question = getPrompt(getState());

    const payload =
      entityType === "model"
        ? await MetabotApi.modelPrompt({ modelId: entityId, question })
        : await MetabotApi.databasePrompt({ databaseId: entityId, question });

    dispatch({ type: FETCH_QUESTION, payload });
  };

export const FETCH_QUERY_RESULTS = "metabase/metabot/FETCH_QUERY_RESULTS";
export const fetchQueryResults =
  () => async (dispatch: Dispatch, getState: GetState) => {
    const question = getQuestion(getState());
    const payload = await question?.apiGetResults();
    dispatch({ type: FETCH_QUERY_RESULTS, payload });
  };

export const SUBMIT_FEEDBACK_FORM = "metabase/metabot/SUBMIT_FEEDBACK_FORM";
export const submitFeedbackForm =
  (feedbackType: MetabotFeedbackType) => (dispatch: Dispatch) => {
    dispatch({ type: SUBMIT_FEEDBACK_FORM, payload: feedbackType });
    dispatch(submitFeedback());
  };

export const SUBMIT_FEEDBACK = "metabase/metabot/SUBMIT_FEEDBACK";
export const submitFeedback =
  () => (dispatch: Dispatch, getState: GetState) => {
    const prompt = getPrompt(getState());
    const entityType = getEntityType(getState());
    const sql = getNativeQueryText(getState());
    const feedbackType = getFeedbackType(getState());

    MetabotApi.sendFeedback({
      entity_type: entityType,
      prompt,
      sql,
      feedback: feedbackType,
    });

    dispatch({ type: SUBMIT_FEEDBACK });
  };