/**
 * @ngdoc function
 * @name fstepApp.controller:NavbarCtrl
 * @description
 * # NavbarCtrl
 * Controller of the navbar
 */
define(['../fstepmodules'], function (fstepmodules) {
    'use strict';

    fstepmodules.controller('NavbarCtrl', ['fstepProperties', '$scope', '$location', 'UserService', '$window', function (fstepProperties, $scope, $location, UserService, $window) {

        $scope.user = undefined;
        $scope.ssoUrl = fstepProperties.SSO_URL;
        $scope.fstepUrl = fstepProperties.FSTEP_URL;

        $scope.isActive = function (route) {
            return route === $location.path();
        };

        UserService.getCurrentUser().then(function(data){
            $scope.user = data;
        });

        $scope.reloadRoute = function() {
           $window.location.reload();
        };

        $scope.$on('no.user', function() {
            $scope.user = "Guest";
        });

    }]);
});
