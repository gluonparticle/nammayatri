module Domain.Action.ProviderPlatform.Operator.Registration (postOperatorRegister) where

import qualified API.Client.ProviderPlatform.Operator as Client
import qualified API.Types.ProviderPlatform.Operator.Registration as Common
import qualified Domain.Action.Dashboard.Person as DP
import qualified "lib-dashboard" Domain.Types.Merchant as DM
import qualified Domain.Types.Person.Type as DP
import qualified Domain.Types.Role as DRole
import qualified Domain.Types.Transaction as DT
import "lib-dashboard" Environment
import Kernel.External.Encryption (encrypt)
import Kernel.Prelude
import Kernel.Types.APISuccess (APISuccess (..))
import qualified Kernel.Types.Beckn.Context as Context
import Kernel.Types.Common
import Kernel.Types.Error
import Kernel.Types.Id
import Kernel.Types.Predicate
import Kernel.Utils.Common
import qualified Kernel.Utils.Predicates as P
import Kernel.Utils.Validation
import qualified SharedLogic.Transaction as ST
import Storage.Beam.CommonInstances ()
import qualified "lib-dashboard" Storage.Queries.Merchant as QMerchant
import qualified Storage.Queries.MerchantAccess as QAccess
import qualified "lib-dashboard" Storage.Queries.Person as QP
import qualified Storage.Queries.Role as QRole
import Tools.Auth.Api
import Tools.Auth.Merchant
import "lib-dashboard" Tools.Error

postOperatorRegister ::
  ShortId DM.Merchant ->
  Context.City ->
  ApiTokenInfo ->
  Common.OperatorRegisterReq ->
  Flow APISuccess
postOperatorRegister merchantShortId opCity apiTokenInfo req = do
  runRequestValidation validateOperator req
  checkedMerchantId <- merchantCityAccessCheck merchantShortId apiTokenInfo.merchant.shortId opCity apiTokenInfo.city
  transaction <- ST.buildTransaction (DT.castEndpoint apiTokenInfo.userActionType) (Just DRIVER_OFFER_BPP_MANAGEMENT) (Just apiTokenInfo) Nothing Nothing (Just req)
  res <- ST.withTransactionStoring transaction do
    Client.callOperatorAPI checkedMerchantId opCity (.registrationDSL.postOperatorRegister) req
  registerOperator merchantShortId opCity req res.personId
  pure Success

registerOperator ::
  ShortId DM.Merchant ->
  Context.City ->
  Common.OperatorRegisterReq ->
  Id Common.Operator ->
  Flow ()
registerOperator merchantShortId opCity req operatorId = do
  unlessM (isNothing <$> QP.findByMobileNumber req.mobileNumber req.mobileCountryCode) $ throwError (InvalidRequest "Phone already registered")
  operatorRole <- QRole.findByDashboardAccessType DRole.DASHBOARD_OPERATOR >>= fromMaybeM (RoleDoesNotExist "OPERATOR")
  operator <- buildOperator req operatorId operatorRole
  merchant <-
    QMerchant.findByShortId merchantShortId
      >>= fromMaybeM (MerchantDoesNotExist merchantShortId.getShortId)
  merchantServerAccessCheck merchant
  merchantAccess <- DP.buildMerchantAccess operator.id merchant.id merchant.shortId opCity
  QP.create operator
  QAccess.create merchantAccess

buildOperator :: (EncFlow m r) => Common.OperatorRegisterReq -> Id Common.Operator -> DRole.Role -> m DP.Person
buildOperator req operatorId role = do
  now <- getCurrentTime
  mobileNumber <- encrypt req.mobileNumber
  email <- forM req.email encrypt
  return
    DP.Person
      { id = cast @Common.Operator @DP.Person operatorId,
        firstName = req.firstName,
        lastName = req.lastName,
        roleId = role.id,
        email = email,
        mobileNumber = mobileNumber,
        mobileCountryCode = req.mobileCountryCode,
        passwordHash = Nothing,
        dashboardAccessType = Just role.dashboardAccessType,
        receiveNotification = Nothing,
        createdAt = now,
        updatedAt = now,
        verified = Just True,
        rejectionReason = Nothing,
        rejectedAt = Nothing
      }

validateOperator :: Validate Common.OperatorRegisterReq
validateOperator Common.OperatorRegisterReq {..} =
  sequenceA_
    [ validateField "firstName" firstName $ MinLength 3 `And` P.name,
      validateField "lastName" lastName $ NotEmpty `And` P.name,
      validateField "mobileNumber" mobileNumber P.mobileNumber,
      validateField "mobileCountryCode" mobileCountryCode P.mobileCountryCode
    ]
