/**
 * @name Debug result inclusion
 * @description Use this query to understand why some alerts are included or excluded from the
 *              results of boosted queries. The results for this query are the union of the alerts
 *              generated by each boosted query. Each alert includes an explanation why it was
 *              included or excluded for each of the four security queries.
 * @kind problem
 * @problem.severity error
 * @id adaptive-threat-modeling/js/debug-result-inclusion
 */

import javascript
import experimental.adaptivethreatmodeling.ATMConfig
import extraction.ExtractEndpointData

string getAReasonSinkExcluded(DataFlow::Node sinkCandidate, Query query) {
  query instanceof NosqlInjectionQuery and
  result = NosqlInjectionAtm::SinkEndpointFilter::getAReasonSinkExcluded(sinkCandidate)
  or
  query instanceof SqlInjectionQuery and
  result = SqlInjectionAtm::SinkEndpointFilter::getAReasonSinkExcluded(sinkCandidate)
  or
  query instanceof TaintedPathQuery and
  result = TaintedPathAtm::SinkEndpointFilter::getAReasonSinkExcluded(sinkCandidate)
  or
  query instanceof XssQuery and
  result = XssAtm::SinkEndpointFilter::getAReasonSinkExcluded(sinkCandidate)
}

pragma[inline]
string getDescriptionForAlertCandidate(
  DataFlow::Node sourceCandidate, DataFlow::Node sinkCandidate, Query query
) {
  result = "excluded[reason=" + getAReasonSinkExcluded(sinkCandidate, query) + "]"
  or
  getAtmCfg(query).isKnownSink(sinkCandidate) and
  result = "excluded[reason=known-sink]"
  or
  not exists(getAReasonSinkExcluded(sinkCandidate, query)) and
  not getDataFlowCfg(query).hasFlow(sourceCandidate, sinkCandidate) and
  (
    if
      getDataFlowCfg(query).isSource(sourceCandidate) or
      getDataFlowCfg(query).isSource(sourceCandidate, _)
    then result = "no flow"
    else result = "not a known source"
  )
  or
  getDataFlowCfg(query).hasFlow(sourceCandidate, sinkCandidate) and
  result = "included"
}

pragma[inline]
string getDescriptionForAlert(DataFlow::Node sourceCandidate, DataFlow::Node sinkCandidate) {
  result =
    concat(Query query |
      |
      query.getName() + ": " +
          getDescriptionForAlertCandidate(sourceCandidate, sinkCandidate, query), ", "
    )
}

from DataFlow::Configuration cfg, DataFlow::Node source, DataFlow::Node sink
where cfg.hasFlow(source, sink)
select sink,
  "This is an ATM result that may depend on $@ [" + getDescriptionForAlert(source, sink) + "]",
  source, "a user-provided value"
